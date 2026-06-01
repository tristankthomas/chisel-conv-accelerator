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

  val MAX_INFLIGHT = 16
  val PARALLEL = 4 // output elements computed per cycle
  val FP_PARALLEL = 2 // fp bus-limited to 2x32=64 bits

  // funct7 encodings
  val FUNC_SET_INPUT = 0.U
  val FUNC_START_INT = 1.U
  val FUNC_START_FP = 2.U
  val FUNC_POLL_STATUS = 3.U

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
  val rowsLoaded = RegInit(0.U(6.W))
  val loadReqIdx = RegInit(0.U(10.W))
  val loadRespIdx = RegInit(0.U(10.W))
  val loadInflight = RegInit(0.U(4.W))

  // kernel buffer
  val kernelBuffer = RegInit(VecInit(Seq.fill(25)(0.U(32.W))))

  // output streaming registers
  val stagingVal = RegInit(0.U(64.W))
  val stagingValid = RegInit(false.B) // true when stagingVal has fresh data ready for the bus
  val computeIdx = RegInit(0.U(11.W))

  // compute counters
  val outRow = RegInit(0.U(5.W))
  val outCol = RegInit(0.U(5.W))

  // store counters
  val storeReqIdx = RegInit(0.U(10.W))
  val storeRespIdx = RegInit(0.U(10.W))
  val storeInflight = RegInit(0.U(4.W))

  // kernel load counters
  val kernelReqIdx = RegInit(0.U(5.W))
  val kernelRespIdx = RegInit(0.U(5.W))
  val kernelInflight = RegInit(0.U(4.W))

  // padding offset
  val pad = kernelSize >> 1

  // PARALLEL fixed point convolution engines
  val convs = Seq.fill(PARALLEL)(Module(new Convolution()))
  convs.foreach(_.io.kernel := VecInit(kernelBuffer.map(_(15, 0))))

  // FP_PARALLEL floating point convolution engines (only instantiate 2)
  val convFPs = Seq.fill(FP_PARALLEL)(Module(new ConvolutionFP()))
  convFPs.foreach(_.io.kernel := kernelBuffer)

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
    loadReqIdx := 0.U
    loadRespIdx := 0.U
    loadInflight := 0.U
    storeReqIdx := 0.U
    storeRespIdx := 0.U
    storeInflight := 0.U
    kernelReqIdx := 0.U
    kernelRespIdx := 0.U
    kernelInflight := 0.U
    stagingVal := 0.U
    stagingValid := false.B
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

  // combinational window construction
  for (p <- 0 until PARALLEL) {
    for (ki <- 0 until 5) {
      for (kj <- 0 until 5) {
        val ii = outRow +& ki.U - pad
        val jj = (outCol + p.U) +& kj.U - pad
        val inBounds = ii < 32.U && jj < 32.U
        val rowIdx = ii % 6.U
        
        // Integer modules (0 to 3) always get wired
        convs(p).io.window(ki * 5 + kj) := Mux(inBounds, lineBuffer(rowIdx)(jj)(15, 0), 0.U(16.W))
        
        // FP modules (0 to 1) only get wired for the first 2
        if (p < FP_PARALLEL) {
            convFPs(p).io.window(ki * 5 + kj) := Mux(inBounds, lineBuffer(rowIdx)(jj), 0.U(32.W))
        }
      }
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
      val intResults = VecInit(convs.map(_.io.result >> 8))
      val fpResults  = VecInit(convFPs.map(_.io.result))

      val respFire = io.mem.resp.valid
      val isLoadResp = !io.mem.resp.bits.tag(4)

      // divide by 8 (int) or 16 (float) to get current row requested
      val rowsRequested = Mux(isFloatMode, loadReqIdx >> 4, loadReqIdx >> 3)
      // keep fetcher strictly within 6 row lookahead window relative to compute engine
      val canFetch = rowsRequested < outRow + (6.U - pad) // note requested not response
      val totalRequests = Mux(isFloatMode, 512.U, 256.U)
      val totalStores = Mux(isFloatMode, 511.U, 255.U)

      // load store arbitration
      val wantLoad = canFetch && loadInflight < MAX_INFLIGHT.U && loadReqIdx < totalRequests
      val wantStore = stagingValid && storeInflight < MAX_INFLIGHT.U

      when(wantLoad) {
        io.mem.req.valid := true.B
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := 3.U
        io.mem.req.bits.tag := Cat(0.U(1.W), loadReqIdx(3, 0))
        io.mem.req.bits.addr := inputAddr + (loadReqIdx << 3)
        when(io.mem.req.ready) { loadReqIdx := loadReqIdx + 1.U }
      } .elsewhen(wantStore) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := outputAddr + (storeReqIdx << 3)
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.size := 3.U
        io.mem.req.bits.tag := Cat(1.U(1.W), storeReqIdx(3, 0))
        io.mem.req.bits.data := stagingVal
        when(io.mem.req.ready) { storeReqIdx := storeReqIdx + 1.U }
      }

      // handle responses
      when(respFire) {
        when(isLoadResp) {
          when(isFloatMode) {
            val base = loadRespIdx << 1
            val row = (base >> 5) % 6.U
            val col0 = base & 31.U
            lineBuffer(row)(col0) := io.mem.resp.bits.data(31, 0)
            lineBuffer(row)(col0 + 1.U) := io.mem.resp.bits.data(63, 32)
            loadRespIdx := loadRespIdx + 1.U
            when(col0 + 1.U === 31.U) { rowsLoaded := rowsLoaded + 1.U }
          } .otherwise {
            val base = loadRespIdx << 2
            val row = (base >> 5) % 6.U
            val col0 = base & 31.U
            lineBuffer(row)(col0) := io.mem.resp.bits.data(15, 0)
            lineBuffer(row)(col0 + 1.U) := io.mem.resp.bits.data(31, 16)
            lineBuffer(row)(col0 + 2.U) := io.mem.resp.bits.data(47, 32)
            lineBuffer(row)(col0 + 3.U) := io.mem.resp.bits.data(63, 48)
            loadRespIdx := loadRespIdx + 1.U
            when(col0 + 3.U === 31.U) { rowsLoaded := rowsLoaded + 1.U }
          }
        } .otherwise {
          storeRespIdx := storeRespIdx + 1.U
          when(storeRespIdx === totalStores) { state := sDONE }
        }
      }

      val loadReqFire = wantLoad && io.mem.req.ready
      val storeReqFire = wantStore && !wantLoad && io.mem.req.ready
      val loadRespFire = respFire && isLoadResp
      val storeRespFire = respFire && !isLoadResp

      when(loadReqFire && !loadRespFire) { loadInflight := loadInflight + 1.U }
      .elsewhen(!loadReqFire && loadRespFire) { loadInflight := loadInflight - 1.U }

      when(storeReqFire && !storeRespFire) { storeInflight := storeInflight + 1.U }
      .elsewhen(!storeReqFire && storeRespFire) { storeInflight := storeInflight - 1.U }

      // only compute when enough rows are loaded and stop at 32
      val rawNeeded = outRow +& kernelSize - pad
      val rowsNeeded = Mux(rawNeeded > 32.U, 32.U, rawNeeded)
      val enoughRows = rowsLoaded >= rowsNeeded
      
      // compute fires if we have rows AND the buffer isn't full (or is emptying this cycle)
      val canCompute = computeIdx < 1024.U && enoughRows && (!stagingValid || storeReqFire)

      when(canCompute) {
        val colStep = Mux(isFloatMode, FP_PARALLEL.U, PARALLEL.U)
        val colEnd = Mux(isFloatMode, (32 - FP_PARALLEL).U, (32 - PARALLEL).U)
        
        computeIdx := computeIdx + colStep

        when(outCol === colEnd) {
          outCol := 0.U
          when(outRow =/= 31.U) { outRow := outRow + 1.U }
        } .otherwise {
          outCol := outCol + colStep
        }

        // always latch exactly 64 bits and mark valid
        stagingVal := Mux(isFloatMode, 
          Cat(fpResults(1), fpResults(0)), 
          Cat(intResults(3)(15,0), intResults(2)(15,0), intResults(1)(15,0), intResults(0)(15,0))
        )
        stagingValid := true.B
      } .elsewhen(storeReqFire) {
        // if we fired a store but DID NOT compute new data, the buffer is now empty
        stagingValid := false.B
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