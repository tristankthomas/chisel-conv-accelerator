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
  val sIDLE :: sLOAD_KERNEL :: sSTREAM :: sDONE :: Nil = Enum(4)
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

  // line buffer - 6 rows max to allow N+1 streaming for 5x5 kernels
  val lineBuffer = RegInit(VecInit(Seq.fill(6)(VecInit(Seq.fill(32)(0.U(32.W))))))
  val rowsLoaded = RegInit(0.U(6.W))      // total rows loaded so far
  val loadRowReqIdx = RegInit(0.U(11.W))  // input load request index
  val loadRowRespIdx = RegInit(0.U(11.W)) // input load response index
  val loadInflight = RegInit(0.U(4.W))    // inflight input load requests

  // kernel buffer
  val kernelBuffer = RegInit(VecInit(Seq.fill(25)(0.U(32.W))))

  // output streaming registers - buffer 2 elements then fire 64-bit store
  val stagingVal = RegInit(0.U(32.W))
  val stagingFull = RegInit(false.B)
  val computeIdx = RegInit(0.U(11.W))

  // compute counters
  val outRow = RegInit(0.U(5.W))
  val outCol = RegInit(0.U(5.W))

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
    rowsLoaded := 0.U
    loadRowReqIdx := 0.U
    loadRowRespIdx := 0.U
    loadInflight := 0.U
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
      state := sLOAD_KERNEL
    } .otherwise {
      error := true.B
      done := true.B
      state := sDONE
    }
  }

// combinational window construction using absolute ring buffer
  for (ki <- 0 until 5) {
    for (kj <- 0 until 5) {
      val ii = outRow +& ki.U - pad
      val jj = outCol +& kj.U - pad
      val inBounds = ii < 32.U && jj < 32.U
      
      // map the absolute image row directly to its physical 6-slot location
      val rowIdx = ii % 6.U
      
      // fixed point gets lower 16 bits
      conv.io.window(ki * 5 + kj) := Mux(inBounds, lineBuffer(rowIdx)(jj)(15, 0), 0.U(16.W))
      // fp gets full 32 bits
      convFP.io.window(ki * 5 + kj) := Mux(inBounds, lineBuffer(rowIdx)(jj), 0.U(32.W))
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

    // load kernel upfront before streaming
    is(sLOAD_KERNEL) {
      val kernelLen = (kernelSize * kernelSize)(5, 0)
      val reqFire = kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen && io.mem.req.ready
      val respFire = io.mem.resp.valid

      when(kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := kernelAddr + Mux(isFloatMode, kernelReqIdx << 2, kernelReqIdx << 1)
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := Mux(isFloatMode, 2.U, 1.U)
        io.mem.req.bits.tag := kernelReqIdx(3, 0)
      }
      when(reqFire) { kernelReqIdx := kernelReqIdx + 1.U }
      when(respFire) {
        kernelBuffer(kLoadRow * 5.U + kLoadCol) := Mux(isFloatMode,
          io.mem.resp.bits.data(31, 0),
          io.mem.resp.bits.data(15, 0))
        when(kLoadCol === kernelSize - 1.U) {
          kLoadCol := 0.U
          kLoadRow := kLoadRow + 1.U
        } .otherwise { kLoadCol := kLoadCol + 1.U }
        kernelRespIdx := kernelRespIdx + 1.U
        when(kernelRespIdx === kernelLen - 1.U) { state := sSTREAM }
      }
      when(reqFire && !respFire) { kernelInflight := kernelInflight + 1.U }
      .elsewhen(!reqFire && respFire) { kernelInflight := kernelInflight - 1.U }
    }

    // stream: load input rows, compute, and store output concurrently
    is(sSTREAM) {
      val result = Mux(isFloatMode, convFP.io.result, conv.io.result >> 8)
      val respFire = io.mem.resp.valid
      // tag bit4=1 marks store, bit4=0 marks load - reliable across cache pipeline
      val isLoadResp = !io.mem.resp.bits.tag(4)

      // fetcher throttle - keep 5 rows in the buffer max, leaving 1 safe slot
      val rowsRequested = Mux(isFloatMode, loadRowReqIdx >> 4, loadRowReqIdx >> 3)
      val canFetch = rowsRequested < outRow + (6.U - pad)

      // 64-bit loads: fp=512 requests (2x32-bit), int=256 requests (4x16-bit)
      val totalRequests = Mux(isFloatMode, 512.U, 256.U)

      // bus arbitration - stores take priority to unblock compute
      val wantLoad = canFetch && loadInflight < MAX_INFLIGHT.U && loadRowReqIdx < totalRequests
      val wantStore = stagingFull && storeInflight < MAX_INFLIGHT.U

      when(wantStore) {
        // issue store request - tag bit4=1, lower 4 bits = store index (16 unique tags)
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := outputAddr + (storeReqIdx << 3)
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.size := 3.U
        io.mem.req.bits.tag := Cat(1.U(1.W), storeReqIdx(3, 0))
        io.mem.req.bits.data := Cat(result, stagingVal)
        when(io.mem.req.ready) { storeReqIdx := storeReqIdx + 1.U }
      } .elsewhen(wantLoad) {
        // issue 64-bit load - tag bit4=0, lower 4 bits = load index (16 unique tags)
        io.mem.req.valid := true.B
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := 3.U  // 64-bit
        io.mem.req.bits.tag := Cat(0.U(1.W), loadRowReqIdx(3, 0))
        io.mem.req.bits.addr := inputAddr + (loadRowReqIdx << 3)  // 8 bytes per request
        when(io.mem.req.ready) { loadRowReqIdx := loadRowReqIdx + 1.U }
      }

      // handle responses - route by tag bit4
      when(respFire) {
        when(isLoadResp) {
          when(isFloatMode) {
            // unpack 2 x 32-bit floats - always within same row
            val base = loadRowRespIdx << 1
            val row = (base >> 5) % 6.U
            val col0 = base & 31.U
            lineBuffer(row)(col0) := io.mem.resp.bits.data(31, 0)
            lineBuffer(row)(col0 + 1.U) := io.mem.resp.bits.data(63, 32)
            loadRowRespIdx := loadRowRespIdx + 1.U
            when(col0 + 1.U === 31.U) { rowsLoaded := rowsLoaded + 1.U }
          } .otherwise {
            // unpack 4 x 16-bit fixed point - always within same row
            val base = loadRowRespIdx << 2
            val row = (base >> 5) % 6.U
            val col0 = base & 31.U
            lineBuffer(row)(col0) := io.mem.resp.bits.data(15, 0)
            lineBuffer(row)(col0 + 1.U) := io.mem.resp.bits.data(31, 16)
            lineBuffer(row)(col0 + 2.U) := io.mem.resp.bits.data(47, 32)
            lineBuffer(row)(col0 + 3.U) := io.mem.resp.bits.data(63, 48)
            loadRowRespIdx := loadRowRespIdx + 1.U
            when(col0 + 3.U === 31.U) { rowsLoaded := rowsLoaded + 1.U }
          }
        } .otherwise {
          // store response
          storeRespIdx := storeRespIdx + 1.U
          when(storeRespIdx === 511.U) { state := sDONE }
        }
      }

      // inflight counters with net change pattern
      val loadReqFire = wantLoad && !wantStore && io.mem.req.ready
      val storeReqFire = wantStore && io.mem.req.ready
      val loadRespFire = respFire && isLoadResp
      val storeRespFire = respFire && !isLoadResp

      when(loadReqFire && !loadRespFire) { loadInflight := loadInflight + 1.U }
      .elsewhen(!loadReqFire && loadRespFire) { loadInflight := loadInflight - 1.U }

      when(storeReqFire && !storeRespFire) { storeInflight := storeInflight + 1.U }
      .elsewhen(!storeReqFire && storeRespFire) { storeInflight := storeInflight - 1.U }

      // compute - only when enough rows loaded for current window
      val rawNeeded = outRow +& kernelSize - pad
      val rowsNeeded = Mux(rawNeeded > 32.U, 32.U, rawNeeded)
      val enoughRows = rowsLoaded >= rowsNeeded
      val reqFireStore = wantStore && io.mem.req.ready
      val canCompute = computeIdx < 1024.U && enoughRows && (!stagingFull || reqFireStore)

      when(canCompute) {
        computeIdx := computeIdx + 1.U
        when(outCol === 31.U) {
          outCol := 0.U
          when(outRow =/= 31.U) {
            outRow := outRow + 1.U
          }
        } .otherwise {
          outCol := outCol + 1.U
        }
        when(!stagingFull) {
          stagingVal := result
          stagingFull := true.B
        } .otherwise {
          stagingFull := false.B
        }
      }
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