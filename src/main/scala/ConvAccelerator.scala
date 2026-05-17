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

  // funct7 encodings
  val FUNC_SET_INPUT = 0.U
  val FUNC_START = 1.U
  val FUNC_POLL_STATUS = 2.U

  // FSM states
  val sIDLE :: sLOAD_INPUT :: sLOAD_KERNEL :: sCOMPUTE :: sSTORE :: sWAIT_LAST_STORE :: sDONE :: Nil = Enum(7)
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

  // internal buffers
  val inputBuffer = RegInit(VecInit(Seq.fill(32)(VecInit(Seq.fill(32)(0.U(16.W))))))
  val kernelBuffer = RegInit(VecInit(Seq.fill(25)(0.U(16.W))))
  val outputBuffer = RegInit(VecInit(Seq.fill(32)(VecInit(Seq.fill(32)(0.U(32.W))))))

  // compute counters
  val outRow = RegInit(0.U(5.W))
  val outCol = RegInit(0.U(5.W))

  val loadIdx = RegInit(0.U(11.W))
  val storeIdx = RegInit(0.U(11.W))

  val reqPending = RegInit(false.B)
  val storeRespCount = RegInit(0.U(11.W))

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
    loadIdx := 0.U
    storeIdx := 0.U
    storeRespCount := 0.U
    outRow := 0.U
    outCol := 0.U
    reqPending := false.B

    val validKernel = (cmd.bits.rs2 === 1.U) || (cmd.bits.rs2 === 3.U) || (cmd.bits.rs2 === 5.U)
    when(validKernel) {
      state := sLOAD_INPUT
    } .otherwise {
      error := true.B
      done := true.B
      state := sDONE
    }
  }


  // combinational window construction - generates 25 assigns
  for (ki <- 0 until 5) {
    for (kj <- 0 until 5) {
      val ii = outRow +& ki.U - pad
      val jj = outCol +& kj.U - pad
      val inBounds = ii < 32.U && jj < 32.U
      conv.io.window(ki * 5 + kj) := Mux(inBounds, inputBuffer(ii)(jj), 0.U(16.W))
    }
  }


  io.mem.req.valid := false.B
  io.mem.req.bits := DontCare
  io.mem.req.bits.phys := true.B

  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B


  
  // naive FSM
  switch(state) {
    is(sIDLE) { }

    // load entire input matrix
    is(sLOAD_INPUT) {
      when(!reqPending) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := inputAddr + (loadIdx << 1)
        io.mem.req.bits.cmd := 0.U
        io.mem.req.bits.size := 1.U
        io.mem.req.bits.tag := 0.U
        when(io.mem.req.ready) {
          reqPending := true.B
        }
      }
      when(io.mem.resp.valid && reqPending) {
        reqPending := false.B
        inputBuffer(loadIdx >> 5)(loadIdx & 31.U) := io.mem.resp.bits.data(15, 0)
        when(loadIdx === 1023.U) {
          loadIdx := 0.U
          state := sLOAD_KERNEL
        } .otherwise {
          loadIdx := loadIdx + 1.U
        }
      }
    }

    // load entire kernel
    is(sLOAD_KERNEL) {
      val kernelLen = (kernelSize * kernelSize)(5, 0)
      when(!reqPending) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := kernelAddr + (loadIdx << 1)
        io.mem.req.bits.cmd := 0.U
        io.mem.req.bits.size := 1.U
        io.mem.req.bits.tag := 0.U
        when(io.mem.req.ready) {
          reqPending := true.B
        }
      }
      when(io.mem.resp.valid && reqPending) {
        reqPending := false.B
        kernelBuffer(loadIdx) := io.mem.resp.bits.data(15, 0)
        when(loadIdx === kernelLen - 1.U) {
          loadIdx := 0.U
          state := sCOMPUTE
        } .otherwise {
          loadIdx := loadIdx + 1.U
        }
      }
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
    
    // store entire output matrix in memory
    is(sSTORE) {
      io.mem.req.valid := true.B
      // store in groups of 4 bytes (32 bits)
      io.mem.req.bits.addr := outputAddr + (storeIdx << 2)
      io.mem.req.bits.cmd := 1.U
      io.mem.req.bits.size := 2.U
      io.mem.req.bits.tag := 0.U
      io.mem.req.bits.data := outputBuffer(storeIdx >> 5)(storeIdx & 31.U)
      when(io.mem.req.ready) {
        when(storeIdx === 1023.U) {
          storeIdx := 0.U
          state := sWAIT_LAST_STORE
        } .otherwise {
          storeIdx := storeIdx + 1.U
        }
      }
      // count store responses as they arrive
      when(io.mem.resp.valid) {
        storeRespCount := storeRespCount + 1.U
      }
    }

    // wait until all 1024 store acks received before signalling complete
    is(sWAIT_LAST_STORE) {
      when(io.mem.resp.valid) {
        storeRespCount := storeRespCount + 1.U
      }
      when(storeRespCount === 1024.U) {
        storeRespCount := 0.U
        state := sDONE
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


// function convolve(input[32][32], kernel[K][K], output[32][32]):
//     pad = K / 2
    
//     for i = 0 to 31:
//         for j = 0 to 31:
//             window = extract_window(input, i, j, pad)
//             output[i][j] = conv(window, kernel) >> 8

// function extract_window(input, i, j, pad):
//     for ki = 0 to K-1:
//         for kj = 0 to K-1:
//             ii = i + ki - pad
//             jj = j + kj - pad
//             if ii >= 0 and ii < 32 and jj >= 0 and jj < 32:
//                 window[ki][kj] = input[ii][jj]
//             else:
//                 window[ki][kj] = 0
//     return window

// function conv(window[K][K], kernel[K][K]):
//     return sum(window[i][j] * kernel[i][j] for all i, j)
