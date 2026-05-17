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
  val FUNC_SET_ADDRS = 0.U
  val FUNC_START = 1.U
  val FUNC_POLL_STATUS = 2.U

  // FSM states
  val sIDLE :: sLOAD_INPUT :: sLOAD_KERNEL :: sCOMPUTE :: sSTORE :: sDONE :: Nil = Enum(6)
  val state = RegInit(sIDLE)

  // queue incoming commands
  val cmd = Queue(io.cmd)

  // decode instruction
  val funct7 = cmd.bits.inst.funct
  val doSetAddrs = funct7 === FUNC_SET_ADDRS
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

  // padding offset
  val pad = kernelSize >> 1

  // valid kernel check
  val validKernel = (kernelSize === 1.U) || (kernelSize === 3.U) || (kernelSize === 5.U)

  // convolution engine
  val conv = Module(new Convolution())
  conv.io.kernel := kernelBuffer

  // handle SET_ADDRS
  when(cmd.fire && doSetAddrs) {
    inputAddr := cmd.bits.rs1
    kernelAddr := cmd.bits.rs2
  }

  // handle START
  when(cmd.fire && doStart) {
    outputAddr := cmd.bits.rs1
    kernelSize := cmd.bits.rs2
    done := false.B
    error := false.B
    when(validKernel) {
      state := sLOAD_INPUT
    } .otherwise {
      error := true.B
      done := true.B
      state := sDONE
    }
  }


  // combinational window construction - always active
  for (ki <- 0 until 5) {
    for (kj <- 0 until 5) {
      val ii = outRow +& ki.U - pad
      val jj = outCol +& kj.U - pad
      val inBounds = ii < 32.U && jj < 32.U
      conv.io.window(ki * 5 + kj) := Mux(inBounds, inputBuffer(ii)(jj), 0.U(16.W))
    }
  }
  
  // FSM
  switch(state) {
    is(sIDLE) { }

    is(sLOAD_INPUT) {
    }

    is(sLOAD_KERNEL) {
    }

    is(sCOMPUTE) {
      // store result
      outputBuffer(outRow)(outCol) := conv.io.result >> 8

      // iterate through all elements
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

    is(sSTORE) {
    }

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

  io.mem.req.valid := false.B
  io.mem.req.bits := DontCare
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
