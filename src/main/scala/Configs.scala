package convaccelerator

import chisel3._
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import freechips.rocketchip.tile._

class WithMAC extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val mac = LazyModule.apply(new MAC(OpcodeSet.custom0)(p))
      mac
    }
  )
})


class WithConvAccelerator extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val acc = LazyModule.apply(new ConvAccelerator(OpcodeSet.custom0)(p))
      acc
    }
  )
})