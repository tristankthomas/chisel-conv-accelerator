// Configs.scala
// Rocket Chip configuration fragments for RoCC accelerators.
// integrates custom accelerator lazy modules into the SoC generator.

package convaccelerator

import chisel3._
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import freechips.rocketchip.tile._

// config fragment to instantiate the basic MAC accelerator on custom0
class WithMAC extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val mac = LazyModule.apply(new MAC(OpcodeSet.custom0)(p))
      mac
    }
  )
})

// config fragment to instantiate the full convolution accelerator on custom0
class WithConvAccelerator extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val acc = LazyModule.apply(new ConvAccelerator(OpcodeSet.custom0)(p))
      acc
    }
  )
})