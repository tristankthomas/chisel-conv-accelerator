// ConvolutionTest.scala
// unit tests for fixed-point convolution accelerator.
// verifies basic operations using identity and all-ones kernels in 8.8 format.

package convaccelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ConvolutionTest extends AnyFlatSpec with ChiselScalatestTester {

  "Convolution" should "return centre value for identity kernel" in {
    test(new Convolution()) { dut =>
      // identity kernel in 8.8 fixed point (1.0 = 256 = 0x100)
      // centre element (ki=2, kj=2) = index 12 = 1.0, rest = 0
      for (i <- 0 until 25) {
        dut.io.kernel(i).poke(if (i == 12) 256.U else 0.U)
      }

      // window with known values
      for (i <- 0 until 25) {
        dut.io.window(i).poke((i + 1).U)  // 1..25
      }

      dut.clock.step(1)

      // centre element is index 12, value = 13
      // result = 13 * 256 = 3328, >> 8 = 13
      // but result is before >> 8 so expect 3328
      dut.io.result.expect(3328.U)
    }
  }

  "Convolution" should "sum all elements for all-ones kernel" in {
    test(new Convolution()) { dut =>
      // all-ones kernel in 8.8 (1.0 = 256)
      for (i <- 0 until 25) {
        dut.io.kernel(i).poke(256.U)
      }

      // window all ones in 8.8 (1.0 = 256)
      for (i <- 0 until 25) {
        dut.io.window(i).poke(256.U)
      }

      dut.clock.step(1)

      // 25 * (256 * 256) = 25 * 65536 = 1638400
      dut.io.result.expect(1638400.U)
    }
  }
}