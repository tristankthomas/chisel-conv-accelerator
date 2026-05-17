package convaccelerator

import chisel3._
import chisel3.util._

class Convolution extends Module {
  val io = IO(new Bundle {
    val window  = Input(Vec(25, UInt(16.W)))
    val kernel  = Input(Vec(25, UInt(16.W)))
    val result  = Output(UInt(32.W))
  })

  // generate mul for each element
  val products = VecInit((0 until 25).map(i => io.window(i) * io.kernel(i)))
  // generate adder tree
  io.result := products.reduce(_ + _)

}
