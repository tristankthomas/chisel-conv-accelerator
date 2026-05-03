package convaccelerator

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._

class MAC(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  override lazy val module = new MACModuleImp(this)
}

class MACModuleImp(outer: MAC)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {

  // queue command
  val cmd = Queue(io.cmd) 

  // decode instruction from cmd
  val funct7 = cmd.bits.inst.funct 
  val doWrite = funct7 === 0.U
  val doMAC = funct7 === 1.U
  val doResp = cmd.bits.inst.xd

  // add memory for inputs
  val a0 = RegInit(0.U(xLen.W))
  val a1 = RegInit(0.U(xLen.W))
  val b0 = RegInit(0.U(xLen.W))
  val b1 = RegInit(0.U(xLen.W))
  
  // state
  val load_b = RegInit(false.B) 

  // load in data basd on state
  when(cmd.fire && doWrite) {
    when(!load_b) {
      a0 := cmd.bits.rs1
      a1 := cmd.bits.rs2
      load_b := true.B
    } .otherwise {
      b0 := cmd.bits.rs1
      b1 := cmd.bits.rs2
      load_b := false.B
    }
  }

  // compute MAC
  val out = (a0 * b0) + (a1 * b1)

  // check if CPU is stalled
  val stallResp = doMAC && doResp && !io.resp.ready
  // if not stalled then accelerator ready for command
  cmd.ready := !stallResp

  // accelerator has valid data computed (after doMAC cycle)
  io.resp.valid := cmd.valid && doMAC && doResp && !stallResp
  io.resp.bits.rd := cmd.bits.inst.rd
  io.resp.bits.data := out

  io.busy := cmd.valid && doWrite
  io.interrupt := false.B
  io.mem.req.valid := false.B
  io.mem.req.bits := DontCare
}