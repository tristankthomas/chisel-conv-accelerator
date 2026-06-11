// ConvAccelerator.scala
// RoCC-attached matrix convolution accelerator for a Rocket core.
// Supports 32x32 input with K in {1,3,5} in 8.8 fixed-point and IEEE-754 single-precision FP.
// FSM: sIDLE -> sLOAD_KERNEL -> sSTREAM -> sDONE
// sSTREAM: concurrent row prefetch, parallel compute, and output streaming to memory.

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
  val PARALLEL = 4     // fixed-point outputs per cycle; 4x16 = 64 bits saturates bus
  val FP_PARALLEL = 2  // fp outputs per cycle; 2x32 = 64 bits saturates bus

  // funct7 encodings
  val FUNC_SET_INPUT = 0.U
  val FUNC_START_INT = 1.U
  val FUNC_START_FP = 2.U
  val FUNC_POLL_STATUS = 3.U

  // fsm states
  val sIDLE :: sLOAD_KERNEL :: sSTREAM :: sDONE :: Nil = Enum(4)
  val state = RegInit(sIDLE)

  // queue incoming commands to decouple from cpu pipeline
  val cmd = Queue(io.cmd)

  val funct7 = cmd.bits.inst.funct
  val doSetInput = funct7 === FUNC_SET_INPUT
  val doStart = funct7 === FUNC_START_INT || funct7 === FUNC_START_FP
  val doPoll = funct7 === FUNC_POLL_STATUS
  val doResp = cmd.bits.inst.xd

  val inputAddr = RegInit(0.U(xLen.W))
  val kernelAddr = RegInit(0.U(xLen.W))
  val outputAddr = RegInit(0.U(xLen.W))
  val kernelSize = RegInit(0.U(xLen.W))
  val isFloatMode = RegInit(false.B)

  val done = RegInit(false.B)
  val error = RegInit(false.B)

  // cpu privilege latched at start - attached to all memory requests
  val mem_dprv = RegInit(0.U(2.W))
  val mem_dv = RegInit(false.B)

  val kLoadRow = RegInit(0.U(3.W))
  val kLoadCol = RegInit(0.U(3.W))

  // 6-slot circular line buffer; 32-bit unified slots support both int and fp
  val lineBuffer = RegInit(VecInit(Seq.fill(6)(VecInit(Seq.fill(32)(0.U(32.W))))))
  val rowsLoaded = RegInit(0.U(6.W))
  val loadReqIdx = RegInit(0.U(10.W))
  val loadRespIdx = RegInit(0.U(10.W))
  val loadInflight = RegInit(0.U(4.W))

  // 25-slot kernel buffer; smaller kernels placed in top-left, remainder stays zero
  val kernelBuffer = RegInit(VecInit(Seq.fill(25)(0.U(32.W))))

  // staging register holds one packed 64-bit store payload until bus is free
  val stagingVal = RegInit(0.U(64.W))
  val stagingValid = RegInit(false.B)
  val computeIdx = RegInit(0.U(11.W))

  val outRow = RegInit(0.U(5.W))
  val outCol = RegInit(0.U(5.W))

  val storeReqIdx = RegInit(0.U(10.W))
  val storeRespIdx = RegInit(0.U(10.W))
  val storeInflight = RegInit(0.U(4.W))

  val kernelReqIdx = RegInit(0.U(5.W))
  val kernelRespIdx = RegInit(0.U(5.W))
  val kernelInflight = RegInit(0.U(4.W))

  val pad = kernelSize >> 1

  val convs = Seq.fill(PARALLEL)(Module(new Convolution()))
  val convFPs = Seq.fill(FP_PARALLEL)(Module(new ConvolutionFP()))
  convs.foreach(_.io.kernel := VecInit(kernelBuffer.map(_(15, 0))))
  convFPs.foreach(_.io.kernel := kernelBuffer)

  when(cmd.fire && doSetInput) {
    inputAddr := cmd.bits.rs1
    outputAddr := cmd.bits.rs2
  }

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

    // only odd kernel sizes 1,3,5 supported; others assert error and skip to done
    val validKernel = (cmd.bits.rs2 === 1.U) || (cmd.bits.rs2 === 3.U) || (cmd.bits.rs2 === 5.U)
    when(validKernel) {
      state := sLOAD_KERNEL
    } .otherwise {
      error := true.B
      done := true.B
      state := sDONE
    }
  }

  // window construction: unsigned subtraction overflow produces large value that fails < 32,
  // implementing zero padding without a signed comparator
  for (p <- 0 until PARALLEL) {
    for (ki <- 0 until 5) {
      for (kj <- 0 until 5) {
        val ii = outRow +& ki.U - pad
        val jj = (outCol + p.U) +& kj.U - pad
        val inBounds = ii < 32.U && jj < 32.U
        val slotIdx = ii % 6.U  // absolute modular index; no head pointer needed
        convs(p).io.window(ki * 5 + kj) := Mux(inBounds, lineBuffer(slotIdx)(jj)(15, 0), 0.U(16.W))
        if (p < FP_PARALLEL) {
          convFPs(p).io.window(ki * 5 + kj) := Mux(inBounds, lineBuffer(slotIdx)(jj), 0.U(32.W))
        }
      }
    }
  }

  // default memory request signals; overridden in fsm states
  io.mem.req.valid := false.B
  io.mem.req.bits := DontCare
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.dprv := mem_dprv
  io.mem.req.bits.dv := mem_dv
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.no_resp := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B

  switch(state) {
    is(sIDLE) { }

    // load all k^2 kernel elements sequentially before streaming begins
    is(sLOAD_KERNEL) {
      val kernelLen = (kernelSize * kernelSize)(5, 0)
      val reqFire = kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen && io.mem.req.ready
      val respFire = io.mem.resp.valid

      when(kernelInflight < MAX_INFLIGHT.U && kernelReqIdx < kernelLen) {
        io.mem.req.valid := true.B
        // 32-bit reads for fp, 16-bit for int
        io.mem.req.bits.addr := kernelAddr + Mux(isFloatMode, kernelReqIdx << 2, kernelReqIdx << 1)
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := Mux(isFloatMode, 2.U, 1.U)
        io.mem.req.bits.tag := kernelReqIdx(3, 0)
      }
      when(reqFire) { kernelReqIdx := kernelReqIdx + 1.U }
      when(respFire) {
        // hellaCache aligns sub-word reads to lsb - no lane steering needed
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

    // concurrent input prefetch, compute, and output store
    is(sSTREAM) {
      val intResults = VecInit(convs.map(_.io.result >> 8))
      val fpResults = VecInit(convFPs.map(_.io.result))

      val respFire = io.mem.resp.valid
      val isLoadResp = !io.mem.resp.bits.tag(4)  // tag bit4=0 load, bit4=1 store

      // throttle on rows requested (not received) to prevent in-flight requests
      // from overwriting slots still needed by the compute engine
      val rowsRequested = Mux(isFloatMode, loadReqIdx >> 4, loadReqIdx >> 3)
      val canFetch = rowsRequested < outRow + (6.U - pad)
      val totalRequests = Mux(isFloatMode, 512.U, 256.U)
      val totalStores = Mux(isFloatMode, 511.U, 255.U)

      // load priority: prefetcher never starved by store traffic
      val wantLoad = canFetch && loadInflight < MAX_INFLIGHT.U && loadReqIdx < totalRequests
      val wantStore = stagingValid && storeInflight < MAX_INFLIGHT.U

      // valid never depends on ready - avoids combinational loop through cache handshake
      when(wantLoad) {
        io.mem.req.valid := true.B
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := 3.U  // 64-bit; 4x16-bit int or 2x32-bit fp per request
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

      // route responses by tag bit4; unpack into line buffer slots
      when(respFire) {
        when(isLoadResp) {
          when(isFloatMode) {
            // unpack 2x32-bit floats; always within same row
            val base = loadRespIdx << 1
            val row = (base >> 5) % 6.U
            val col0 = base & 31.U
            lineBuffer(row)(col0) := io.mem.resp.bits.data(31, 0)
            lineBuffer(row)(col0 + 1.U) := io.mem.resp.bits.data(63, 32)
            loadRespIdx := loadRespIdx + 1.U
            when(col0 + 1.U === 31.U) { rowsLoaded := rowsLoaded + 1.U }
          } .otherwise {
            // unpack 4x16-bit fixed-point; always within same row
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

      // net-change pattern: only update inflight on unmatched req/resp pairs
      val loadReqFire = wantLoad && io.mem.req.ready
      val storeReqFire = wantStore && !wantLoad && io.mem.req.ready
      val loadRespFire = respFire && isLoadResp
      val storeRespFire = respFire && !isLoadResp

      when(loadReqFire && !loadRespFire) { loadInflight := loadInflight + 1.U }
      .elsewhen(!loadReqFire && loadRespFire) { loadInflight := loadInflight - 1.U }
      when(storeReqFire && !storeRespFire) { storeInflight := storeInflight + 1.U }
      .elsewhen(!storeReqFire && storeRespFire) { storeInflight := storeInflight - 1.U }

      // stall compute until required rows are present; clamp at 32 for final rows
      val rawNeeded = outRow +& kernelSize - pad
      val rowsNeeded = Mux(rawNeeded > 32.U, 32.U, rawNeeded)
      val enoughRows = rowsLoaded >= rowsNeeded

      // compute fires only when staging is empty or draining this cycle;
      // back-pressure ensures compute never outruns the memory bus
      val canCompute = computeIdx < 1024.U && enoughRows && (!stagingValid || storeReqFire)

      when(canCompute) {
        val colStep = Mux(isFloatMode, FP_PARALLEL.U, PARALLEL.U)
        val colEnd = Mux(isFloatMode, (32 - FP_PARALLEL).U, (32 - PARALLEL).U)
        computeIdx := computeIdx + colStep
        // advance output position by parallelism factor each cycle
        when(outCol === colEnd) {
          outCol := 0.U
          when(outRow =/= 31.U) { outRow := outRow + 1.U }
        } .otherwise {
          outCol := outCol + colStep
        }
        // pack parallel results into 64-bit staging register and mark ready for store
        stagingVal := Mux(isFloatMode,
          Cat(fpResults(1), fpResults(0)),
          Cat(intResults(3)(15,0), intResults(2)(15,0), intResults(1)(15,0), intResults(0)(15,0))
        )
        stagingValid := true.B
      } .elsewhen(storeReqFire) {
        stagingValid := false.B  // store fired without new compute - staging now empty
      }
    }

    is(sDONE) {
      done := true.B
      state := sIDLE
    }
  }

  // status word: bit0 = done, bit1 = error
  val statusWord = Cat(error, done)

  // stall cmd queue while waiting for resp handshake
  val stallResp = doPoll && doResp && !io.resp.ready

  cmd.ready := !stallResp
  io.resp.valid := cmd.valid && doPoll && doResp && !stallResp
  io.resp.bits.rd := cmd.bits.inst.rd
  io.resp.bits.data := statusWord
  io.busy := state =/= sIDLE
  io.interrupt := false.B
}