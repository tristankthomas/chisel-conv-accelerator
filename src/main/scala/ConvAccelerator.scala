package convaccelerator

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._

class ConvAccelerator(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  override lazy val module = new ConvAcceleratorModuleImp(this)
}

class ConvAcceleratorModuleImp(outer: ConvAccelerator)(implicit p: Parameters)
    extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {

  val MAX_INFLIGHT = 8

  // funct7 encodings
  val FUNC_SET_INPUT = 0.U
  val FUNC_START = 1.U
  val FUNC_POLL_STATUS = 2.U

  // FSM states
  val sIDLE :: sLOAD_INPUT :: sLOAD_KERNEL :: sCOMPUTE :: sSTORE :: sDONE :: Nil = Enum(6)
  val state = RegInit(sIDLE)

  // queue incoming commands
  val cmd = Queue(io.cmd)

  // decode instruction
  val funct7 = cmd.bits.inst.funct
  val doSetInput = funct7 === FUNC_SET_INPUT
  val doStart = funct7 === FUNC_START
  val doPoll = funct7 === FUNC_POLL_STATUS
  val doResp = cmd.bits.inst.xd

  // internal config registers
  val inputAddr = RegInit(0.U(xLen.W))
  val kernelAddr = RegInit(0.U(xLen.W))
  val outputAddr = RegInit(0.U(xLen.W))
  val kernelSize = RegInit(0.U(xLen.W))

  // status registers
  val done = RegInit(false.B)
  val error = RegInit(false.B)

  // latch CPU privilege status for memory requests
  val mem_dprv = RegInit(0.U(2.W))
  val mem_dv = RegInit(false.B)

  val kLoadRow = RegInit(0.U(3.W))
  val kLoadCol = RegInit(0.U(3.W))

  // internal buffers
  val inputBuffer = RegInit(VecInit(Seq.fill(32)(VecInit(Seq.fill(32)(0.U(16.W))))))
  val kernelBuffer = RegInit(VecInit(Seq.fill(25)(0.U(16.W))))
  val outputBuffer = RegInit(VecInit(Seq.fill(32)(VecInit(Seq.fill(32)(0.U(32.W))))))

  // compute counters
  val outRow = RegInit(0.U(5.W))
  val outCol = RegInit(0.U(5.W))

  // pipelined load counters
  val reqIdx = RegInit(0.U(11.W))   // next element to request
  val respIdx = RegInit(0.U(11.W))  // next element to receive
  val inflightCount = RegInit(0.U(4.W))  // outstanding requests (max 8)

  // store counters
  val storeReqIdx = RegInit(0.U(11.W))
  val storeRespIdx = RegInit(0.U(11.W))
  val storeInflight = RegInit(0.U(4.W))

  // kernel load counters
  val kernelReqIdx = RegInit(0.U(5.W))
  val kernelRespIdx = RegInit(0.U(5.W))
  val kernelInflight = RegInit(0.U(4.W))

  // padding offset
  val pad = kernelSize >> 1

  // convolution engine
  val conv = Module(new Convolution())
  conv.io.kernel := kernelBuffer

  // handle SET_INPUT
  when(cmd.fire && doSetInput) {
    inputAddr := cmd.bits.rs1
    outputAddr := cmd.bits.rs2
  }

  // handle START
  when(cmd.fire && doStart) {
    kernelAddr := cmd.bits.rs1
    kernelSize := cmd.bits.rs2
    done := false.B
    error := false.B
    reqIdx := 0.U
    respIdx := 0.U
    inflightCount := 0.U
    storeReqIdx := 0.U
    storeRespIdx := 0.U
    storeInflight := 0.U
    kernelReqIdx := 0.U
    kernelRespIdx := 0.U
    kernelInflight := 0.U
    outRow := 0.U
    outCol := 0.U
    kLoadRow := 0.U
    kLoadCol := 0.U
    mem_dprv := cmd.bits.status.dprv
    mem_dv := cmd.bits.status.dv

    val validKernel = (cmd.bits.rs2 === 1.U) || (cmd.bits.rs2 === 3.U) || (cmd.bits.rs2 === 5.U)
    when(validKernel) {
      state := sLOAD_INPUT
    } .otherwise {
      error := true.B
      done := true.B
      state := sDONE
    }
  }

  // combinational window construction
  for (ki <- 0 until 5) {
    for (kj <- 0 until 5) {
      val ii = outRow +& ki.U - pad
      val jj = outCol +& kj.U - pad
      val inBounds = ii < 32.U && jj < 32.U
      conv.io.window(ki * 5 + kj) := Mux(inBounds, inputBuffer(ii)(jj), 0.U(16.W))
    }
  }

  // default memory signals
  io.mem.req.valid := false.B
  io.mem.req.bits := DontCare
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.dprv := mem_dprv
  io.mem.req.bits.dv := mem_dv
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.no_resp := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B

  // FSM
  switch(state) {
    is(sIDLE) { }

    // load entire input matrix with pipelined requests
is(sLOAD_INPUT) {
    // 64-bit loads: 4 elements per request, 256 requests total
    val reqFire = inflightCount < MAX_INFLIGHT.U && reqIdx < 256.U && io.mem.req.ready
    val respFire = io.mem.resp.valid

    when(inflightCount < MAX_INFLIGHT.U && reqIdx < 256.U) {
      io.mem.req.valid := true.B
      io.mem.req.bits.addr := inputAddr + (reqIdx << 3)  // * 8 bytes per request
      io.mem.req.bits.cmd := M_XRD
      io.mem.req.bits.size := 3.U  // 64-bit
      io.mem.req.bits.tag := reqIdx(3, 0)
    }
    when(reqFire) { reqIdx := reqIdx + 1.U }
    when(respFire) {
      // unpack 4 x 16-bit elements from 64-bit response
      val base = respIdx << 2
      inputBuffer(base >> 5)(base & 31.U) := io.mem.resp.bits.data(15, 0)
      inputBuffer((base + 1.U) >> 5)((base + 1.U) & 31.U) := io.mem.resp.bits.data(31, 16)
      inputBuffer((base + 2.U) >> 5)((base + 2.U) & 31.U) := io.mem.resp.bits.data(47, 32)
      inputBuffer((base + 3.U) >> 5)((base + 3.U) & 31.U) := io.mem.resp.bits.data(63, 48)
      respIdx := respIdx + 1.U
      when(respIdx === 255.U) { state := sLOAD_KERNEL }
    }
    when(reqFire && !respFire) { inflightCount := inflightCount + 1.U }
    .elsewhen(!reqFire && respFire) { inflightCount := inflightCount - 1.U }
  }

    // load entire kernel with pipelined requests
    is(sLOAD_KERNEL) {
      // kernel is small (max 25 elements = 50 bytes) keep 16-bit loads for simplicity
      val kernelLen = (kernelSize * kernelSize)(5, 0)
      val reqFire = kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen && io.mem.req.ready
      val respFire = io.mem.resp.valid

      when(kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := kernelAddr + (kernelReqIdx << 1)
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := 1.U
        io.mem.req.bits.tag := kernelReqIdx(3, 0)
      }
      when(reqFire) { kernelReqIdx := kernelReqIdx + 1.U }
      when(respFire) {
        kernelBuffer(kLoadRow * 5.U + kLoadCol) := io.mem.resp.bits.data(15, 0)
        when(kLoadCol === kernelSize - 1.U) {
          kLoadCol := 0.U
          kLoadRow := kLoadRow + 1.U
        } .otherwise { kLoadCol := kLoadCol + 1.U }
        kernelRespIdx := kernelRespIdx + 1.U
        when(kernelRespIdx === kernelLen - 1.U) { state := sCOMPUTE }
      }
      when(reqFire && !respFire) { kernelInflight := kernelInflight + 1.U }
      .elsewhen(!reqFire && respFire) { kernelInflight := kernelInflight - 1.U }
    }

    // compute one element per cycle
    is(sCOMPUTE) {
      outputBuffer(outRow)(outCol) := conv.io.result >> 8
      when(outCol === 31.U) {
        outCol := 0.U
        when(outRow === 31.U) {
          outRow := 0.U
          state := sSTORE
        } .otherwise {
          outRow := outRow + 1.U
        }
      } .otherwise {
        outCol := outCol + 1.U
      }
    }

    // store entire output matrix with pipelined requests
    is(sSTORE) {
      // 64-bit stores: pack 2 x 32-bit elements per request, 512 requests total
      val reqFire = storeInflight < MAX_INFLIGHT.U && storeReqIdx < 512.U && io.mem.req.ready
      val respFire = io.mem.resp.valid

      when(storeInflight < MAX_INFLIGHT.U && storeReqIdx < 512.U) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := outputAddr + (storeReqIdx << 3)  // * 8 bytes per request
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.size := 3.U  // 64-bit
        io.mem.req.bits.tag := storeReqIdx(3, 0)
        val base = storeReqIdx << 1
        val outVal0 = outputBuffer(base >> 5)(base & 31.U)
        val outVal1 = outputBuffer((base + 1.U) >> 5)((base + 1.U) & 31.U)
        io.mem.req.bits.data := Cat(outVal1, outVal0)  // pack 2 elements
      }
      when(reqFire) { storeReqIdx := storeReqIdx + 1.U }
      when(respFire) {
        storeRespIdx := storeRespIdx + 1.U
        when(storeRespIdx === 511.U) { state := sDONE }
      }
      when(reqFire && !respFire) { storeInflight := storeInflight + 1.U }
      .elsewhen(!reqFire && respFire) { storeInflight := storeInflight - 1.U }
    }

    // convolution complete
    is(sDONE) {
      done := true.B
      state := sIDLE
    }
  }

  // status word
  val statusWord = Cat(error, done)

  // stall logic
  val stallResp = doPoll && doResp && !io.resp.ready

  cmd.ready := !stallResp

  io.resp.valid := cmd.valid && doPoll && doResp && !stallResp
  io.resp.bits.rd := cmd.bits.inst.rd
  io.resp.bits.data := statusWord

  io.busy := state =/= sIDLE
  io.interrupt := false.B
}