package convaccelerator

import chisel3._
import chisel3.util._
import hardfloat._
import scala.language.reflectiveCalls

class ConvolutionFP extends Module {
  val io = IO(new Bundle {
    val window = Input(Vec(25, UInt(32.W)))   // IEEE 754 floats
    val kernel = Input(Vec(25, UInt(32.W)))   // IEEE 754 floats
    val result = Output(UInt(32.W))           // IEEE 754 float
  })

  val expWidth = 8
  val sigWidth = 24

  // convert inputs to recoded format and multiply
  val products = (0 until 25).map { i =>
    val mul = Module(new MulRecFN(expWidth, sigWidth))
    mul.io.roundingMode := consts.round_near_even
    mul.io.detectTininess := consts.tininess_afterRounding
    mul.io.a := recFNFromFN(expWidth, sigWidth, io.window(i))
    mul.io.b := recFNFromFN(expWidth, sigWidth, io.kernel(i))
    mul.io.out
  }

  // adder tree using AddRecFN
  def addTree(vals: Seq[UInt]): UInt = {
    if (vals.length == 1) {
      vals.head
    } else {
      val pairs = vals.grouped(2).map {
        case Seq(a, b) =>
          val add = Module(new AddRecFN(expWidth, sigWidth))
          add.io.subOp := false.B
          add.io.roundingMode := consts.round_near_even
          add.io.detectTininess := consts.tininess_afterRounding
          add.io.a := a
          add.io.b := b
          add.io.out
        case Seq(a) => a
      }.toSeq
      addTree(pairs)
    }
  }

  val sumRec = addTree(products)

  // convert result back to IEEE 754
  io.result := fNFromRecFN(expWidth, sigWidth, sumRec)
}