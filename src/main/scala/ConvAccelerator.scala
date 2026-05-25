package convaccelerator

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import scala.language.reflectiveCalls

class ConvAccelerator(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  override lazy val module = new ConvAcceleratorModuleImp(this)
}

class ConvAcceleratorModuleImp(outer: ConvAccelerator)(implicit p: Parameters)
    extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {

  val MAX_INFLIGHT = 8

  // funct7 encodings
  val FUNC_SET_INPUT = 0.U
  val FUNC_START_INT = 1.U
  val FUNC_POLL_STATUS = 2.U
  val FUNC_START_FP = 3.U

  // FSM states
  val sIDLE :: sLOAD_INPUT :: sLOAD_KERNEL :: sCOMPUTE_STORE :: sDONE :: Nil = Enum(5)
  val state = RegInit(sIDLE)

  // queue incoming commands
  val cmd = Queue(io.cmd)

  // decode instruction
  val funct7 = cmd.bits.inst.funct
  val doSetInput = funct7 === FUNC_SET_INPUT
  val doStart = funct7 === FUNC_START_INT || funct7 === FUNC_START_FP
  val doPoll = funct7 === FUNC_POLL_STATUS
  val doResp = cmd.bits.inst.xd

  // internal config registers
  val inputAddr = RegInit(0.U(xLen.W))
  val kernelAddr = RegInit(0.U(xLen.W))
  val outputAddr = RegInit(0.U(xLen.W))
  val kernelSize = RegInit(0.U(xLen.W))
  val isFloatMode = RegInit(false.B)

  // status registers
  val done = RegInit(false.B)
  val error = RegInit(false.B)

  // latch CPU privilege status for memory requests
  val mem_dprv = RegInit(0.U(2.W))
  val mem_dv = RegInit(false.B)

  val kLoadRow = RegInit(0.U(3.W))
  val kLoadCol = RegInit(0.U(3.W))

  // unified 32-bit buffers for both modes
  val inputBuffer = RegInit(VecInit(Seq.fill(32)(VecInit(Seq.fill(32)(0.U(32.W))))))
  val kernelBuffer = RegInit(VecInit(Seq.fill(25)(0.U(32.W))))

  // output streaming registers - buffer 2 elements then fire 64-bit store
  val stagingVal = RegInit(0.U(32.W))
  val stagingFull = RegInit(false.B)
  val computeIdx = RegInit(0.U(11.W))

  // compute counters
  val outRow = RegInit(0.U(5.W))
  val outCol = RegInit(0.U(5.W))

  // pipelined load counters
  val reqIdx = RegInit(0.U(11.W))
  val respIdx = RegInit(0.U(11.W))
  val inflightCount = RegInit(0.U(4.W))

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

  // fixed point convolution engine - uses lower 16 bits of unified buffer
  val conv = Module(new Convolution())
  conv.io.kernel := VecInit(kernelBuffer.map(_(15, 0)))

  // floating point convolution engine - uses full 32 bits
  val convFP = Module(new ConvolutionFP())
  convFP.io.kernel := kernelBuffer

  // handle SET_INPUT
  when(cmd.fire && doSetInput) {
    inputAddr := cmd.bits.rs1
    outputAddr := cmd.bits.rs2
  }

  // handle START (both int and fp)
  when(cmd.fire && doStart) {
    isFloatMode := (funct7 === FUNC_START_FP)
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
    stagingVal := 0.U
    stagingFull := false.B
    computeIdx := 0.U
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

  // combinational window construction for both modules
  for (ki <- 0 until 5) {
    for (kj <- 0 until 5) {
      val ii = outRow +& ki.U - pad
      val jj = outCol +& kj.U - pad
      val inBounds = ii < 32.U && jj < 32.U
      // fixed point gets lower 16 bits
      conv.io.window(ki * 5 + kj) := Mux(inBounds, inputBuffer(ii)(jj)(15, 0), 0.U(16.W))
      // fp gets full 32 bits
      convFP.io.window(ki * 5 + kj) := Mux(inBounds, inputBuffer(ii)(jj), 0.U(32.W))
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

    // load entire input matrix with pipelined 64-bit requests
    is(sLOAD_INPUT) {
      // fp: 512 requests (2x32-bit), int: 256 requests (4x16-bit)
      val maxReq = Mux(isFloatMode, 512.U, 256.U)
      val maxResp = Mux(isFloatMode, 511.U, 255.U)
      val reqFire = inflightCount < MAX_INFLIGHT.U && reqIdx < maxReq && io.mem.req.ready
      val respFire = io.mem.resp.valid

      when(inflightCount < MAX_INFLIGHT.U && reqIdx < maxReq) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := inputAddr + (reqIdx << 3)
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := 3.U
        io.mem.req.bits.tag := reqIdx(3, 0)
      }
      when(reqFire) { reqIdx := reqIdx + 1.U }
      when(respFire) {
        when(isFloatMode) {
          // unpack 2 x 32-bit floats
          val base = respIdx << 1
          inputBuffer(base >> 5)(base & 31.U) := io.mem.resp.bits.data(31, 0)
          inputBuffer((base+1.U) >> 5)((base+1.U) & 31.U) := io.mem.resp.bits.data(63, 32)
        } .otherwise {
          // unpack 4 x 16-bit fixed point into lower 16 bits of 32-bit slots
          val base = respIdx << 2
          inputBuffer(base >> 5)(base & 31.U) := io.mem.resp.bits.data(15, 0)
          inputBuffer((base+1.U) >> 5)((base+1.U) & 31.U) := io.mem.resp.bits.data(31, 16)
          inputBuffer((base+2.U) >> 5)((base+2.U) & 31.U) := io.mem.resp.bits.data(47, 32)
          inputBuffer((base+3.U) >> 5)((base+3.U) & 31.U) := io.mem.resp.bits.data(63, 48)
        }
        respIdx := respIdx + 1.U
        when(respIdx === maxResp) { state := sLOAD_KERNEL }
      }
      when(reqFire && !respFire) { inflightCount := inflightCount + 1.U }
      .elsewhen(!reqFire && respFire) { inflightCount := inflightCount - 1.U }
    }

    // load entire kernel with pipelined requests
    is(sLOAD_KERNEL) {
      val kernelLen = (kernelSize * kernelSize)(5, 0)
      val reqFire = kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen && io.mem.req.ready
      val respFire = io.mem.resp.valid

      when(kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := kernelAddr + Mux(isFloatMode, kernelReqIdx << 2, kernelReqIdx << 1)
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := Mux(isFloatMode, 2.U, 1.U)  // 32-bit for fp, 16-bit for int
        io.mem.req.bits.tag := kernelReqIdx(3, 0)
      }
      when(reqFire) { kernelReqIdx := kernelReqIdx + 1.U }
      when(respFire) {
        // HellaCache automatically aligns sub-word reads to LSBs - no lane steering needed
        kernelBuffer(kLoadRow * 5.U + kLoadCol) := Mux(isFloatMode,
          io.mem.resp.bits.data(31, 0),
          io.mem.resp.bits.data(15, 0))
        when(kLoadCol === kernelSize - 1.U) {
          kLoadCol := 0.U
          kLoadRow := kLoadRow + 1.U
        } .otherwise { kLoadCol := kLoadCol + 1.U }
        kernelRespIdx := kernelRespIdx + 1.U
        when(kernelRespIdx === kernelLen - 1.U) { state := sCOMPUTE_STORE }
      }
      when(reqFire && !respFire) { kernelInflight := kernelInflight + 1.U }
      .elsewhen(!reqFire && respFire) { kernelInflight := kernelInflight - 1.U }
    }

    // compute and stream output directly to memory
    is(sCOMPUTE_STORE) {
      val result = Mux(isFloatMode, convFP.io.result, conv.io.result >> 8)
      val respFire = io.mem.resp.valid
      val reqFireStore = stagingFull && storeInflight < MAX_INFLIGHT.U && io.mem.req.ready

      // issue store request - valid never depends on ready
      when(stagingFull && storeInflight < MAX_INFLIGHT.U) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := outputAddr + (storeReqIdx << 3)
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.size := 3.U
        io.mem.req.bits.tag := storeReqIdx(3, 0)
        io.mem.req.bits.data := Cat(result, stagingVal)
      }

      // advance compute only when not stalled waiting for store
      val canCompute = computeIdx < 1024.U && (!stagingFull || reqFireStore)

      when(canCompute) {
        computeIdx := computeIdx + 1.U
        when(outCol === 31.U) {
          outCol := 0.U
          when(outRow =/= 31.U) { outRow := outRow + 1.U }
        } .otherwise {
          outCol := outCol + 1.U
        }
        when(!stagingFull) {
          stagingVal := result
          stagingFull := true.B
        } .otherwise {
          stagingFull := false.B
          storeReqIdx := storeReqIdx + 1.U
        }
      }

      // handle store responses
      when(respFire) {
        storeRespIdx := storeRespIdx + 1.U
        when(storeRespIdx === 511.U) { state := sDONE }
      }

      // net change inflight counter
      when(reqFireStore && !respFire) { storeInflight := storeInflight + 1.U }
      .elsewhen(!reqFireStore && respFire) { storeInflight := storeInflight - 1.U }
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