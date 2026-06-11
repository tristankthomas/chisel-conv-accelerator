// Convolution.scala
// fully combinational 5x5 fixed-point convolution engine.
// computes one dot product per cycle; instantiated PARALLEL times in ConvAccelerator.

package convaccelerator

import chisel3._
import chisel3.util._

class Convolution extends Module {
  val io = IO(new Bundle {
    val window = Input(Vec(25, UInt(16.W)))   // flattened 5x5 input window
    val kernel = Input(Vec(25, UInt(16.W)))   // flattened 5x5 kernel weights
    val result = Output(UInt(40.W))           // 40-bit accumulator prevents overflow for max 25x(2^16-1)^2
  })

  // 25 parallel multipliers; chisel map elaborates into discrete multiply instances
  val products = VecInit((0 until 25).map(i => io.window(i) * io.kernel(i)))

  // reduce builds a balanced binary adder tree of depth ceil(log2(25)) = 5
  io.result := products.reduce(_ + _)
}