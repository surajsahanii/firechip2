package pwm

import chisel3._
import chisel3.util._
import config.{Parameters, Field}
import uncore.tilelink._
import uncore.tilelink2._
import junctions._
import diplomacy._
import regmapper.{HasRegMap, RegField}
import rocketchip._
import _root_.util.UIntIsOneOf

case class PWMParams(address: BigInt, beatBytes: Int)

class PWMBase(w: Int) extends Module {
  val io = IO(new Bundle {
    val pwmout = Output(Bool())
    val period = Input(UInt(w.W))
    val duty = Input(UInt(w.W))
    val enable = Input(Bool())
  })

  // The counter should count up until period is reached
  val counter = Reg(UInt(w.W))

  when (counter >= (io.period - 1.U)) {
    counter := 0.U
  } .otherwise {
    counter := counter + 1.U
  }

  // If PWM is enabled, pwmout is high when counter < duty
  // If PWM is not enabled, it will always be low
  io.pwmout := io.enable && (counter < io.duty)
}

trait PWMTLBundle extends Bundle {
  val pwmout = Output(Bool())
}

trait PWMTLModule extends Module with HasRegMap {
  val io: PWMTLBundle
  implicit val p: Parameters
  def params: PWMParams

  val w = params.beatBytes * 8
  require(w <= 32)

  // How many clock cycles in a PWM cycle?
  val period = Reg(UInt(w.W))
  // For how many cycles should the clock be high?
  val duty = Reg(UInt(w.W))
  // Is the PWM even running at all?
  val enable = Reg(init = false.B)

  val base = Module(new PWMBase(w))
  io.pwmout := base.io.pwmout
  base.io.period := period
  base.io.duty := duty
  base.io.enable := enable

  regmap(
    0x00 -> Seq(
      RegField(w, period)),
    0x04 -> Seq(
      RegField(w, duty)),
    0x08 -> Seq(
      RegField(1, enable)))
}

class PWMTL(c: PWMParams)(implicit p: Parameters)
  extends TLRegisterRouter(
    c.address, "pwm", Seq("ucbbar,pwm"),
    beatBytes = c.beatBytes)(
      new TLRegBundle(c, _) with PWMTLBundle)(
      new TLRegModule(c, _, _) with PWMTLModule)

trait HasPeripheryPWM extends HasSystemNetworks {
  implicit val p: Parameters

  private val address = 0x2000

  val pwm = LazyModule(new PWMTL(
    PWMParams(address, peripheryBusConfig.beatBytes))(p))

  pwm.node := TLFragmenter(
    peripheryBusConfig.beatBytes, cacheBlockBytes)(peripheryBus.node)
}

trait HasPeripheryPWMModuleImp extends LazyMultiIOModuleImp {
  implicit val p: Parameters
  val outer: HasPeripheryPWM

  val pwmout = IO(Output(Bool()))

  pwmout := outer.pwm.module.io.pwmout
}
