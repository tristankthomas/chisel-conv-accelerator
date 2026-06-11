// ConvolutionFPTest.scala
// unit tests for floating-point convolution accelerator.
// verifies basic operations using identity, all-ones, and zero kernels.

package convaccelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ConvolutionFPTest extends AnyFlatSpec with ChiselScalatestTester {

  // helper functions to convert between Scala Float and 32-bit UInt representations
  def floatBits(f: Float): Long = java.lang.Float.floatToIntBits(f).toLong & 0xFFFFFFFFL
  def bitsToFloat(b: Long): Float = java.lang.Float.intBitsToFloat(b.toInt)

  "ConvolutionFP" should "return centre value for identity kernel" in {
    test(new ConvolutionFP()) { dut =>
      // identity kernel - centre element (index 12) = 1.0, rest = 0.0
      for (i <- 0 until 25) {
        dut.io.kernel(i).poke(if (i == 12) floatBits(1.0f).U else floatBits(0.0f).U)
      }

      // window values 1.0 to 25.0
      for (i <- 0 until 25) {
        dut.io.window(i).poke(floatBits((i + 1).toFloat).U)
      }

      dut.clock.step(1)

      // centre element is index 12, value = 13.0
      // identity kernel so result = 13.0
      val result = bitsToFloat(dut.io.result.peek().litValue.toLong)
      assert(math.abs(result - 13.0f) < 0.001f, s"Expected 13.0 but got $result")
    }
  }

  "ConvolutionFP" should "sum all elements for all-ones kernel" in {
    test(new ConvolutionFP()) { dut =>
      // all-ones kernel
      for (i <- 0 until 25) {
        dut.io.kernel(i).poke(floatBits(1.0f).U)
      }

      // window all 1.0
      for (i <- 0 until 25) {
        dut.io.window(i).poke(floatBits(1.0f).U)
      }

      dut.clock.step(1)

      // 25 * (1.0 * 1.0) = 25.0
      val result = bitsToFloat(dut.io.result.peek().litValue.toLong)
      assert(math.abs(result - 25.0f) < 0.001f, s"Expected 25.0 but got $result")
    }
  }

  "ConvolutionFP" should "return zero for zero kernel" in {
    test(new ConvolutionFP()) { dut =>
      // zero kernel and sequential window values
      for (i <- 0 until 25) {
        dut.io.kernel(i).poke(floatBits(0.0f).U)
        dut.io.window(i).poke(floatBits((i + 1).toFloat).U)
      }

      dut.clock.step(1)

      // assert accumulated result is 0.0
      val result = bitsToFloat(dut.io.result.peek().litValue.toLong)
      assert(math.abs(result) < 0.001f, s"Expected 0.0 but got $result")
    }
  }
}