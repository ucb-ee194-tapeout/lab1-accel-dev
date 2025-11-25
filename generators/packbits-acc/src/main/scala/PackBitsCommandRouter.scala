package packbitsacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class PackBitsCommandRouter()(implicit p: Parameters) extends Module {

    val FUNCT_SFENCE = 0.U
    val FUNCT_SRC_INFO = 1.U
    val FUNCT_DST_INFO = 2.U

    val io = IO(new Bundle {
        val rocc_in = Flipped(Decoupled(new RoCCCommand))

        // val rocc_out = Decoupled(new RoCCResponse)

        val sfence_out = Output(Bool())
        val dmem_status_out = Valid(new RoCCCommand)

        val src_info = Decoupled(new DMAReadInfo)

        val dst_info = Decoupled(new DMAWriteDstInfo)

    })

    val track_dispatched_src_infos = RegInit(0.U(64.W))
    when (io.rocc_in.fire) {
        when (io.rocc_in.bits.inst.funct === FUNCT_SRC_INFO) {
        val next_track_dispatched_src_infos = track_dispatched_src_infos + 1.U
        track_dispatched_src_infos := next_track_dispatched_src_infos
        PackBitsAccLogger.logInfo("dispatched src info commands: current 0x%x, next 0x%x\n",
            track_dispatched_src_infos,
            next_track_dispatched_src_infos)
        }
    }

    when (io.rocc_in.fire) {
        PackBitsAccLogger.logInfo("gotcmd funct %x, rd %x, rs1val %x, rs2val %x\n", 
        io.rocc_in.bits.inst.funct,
        io.rocc_in.bits.inst.rd,
        io.rocc_in.bits.rs1,
        io.rocc_in.bits.rs2)
    }

    io.dmem_status_out.bits <> io.rocc_in.bits
    io.dmem_status_out.valid := io.rocc_in.fire

    val current_funct = io.rocc_in.bits.inst.funct

    val sfence_fire = DecoupledHelper(
        io.rocc_in.valid,
        current_funct === FUNCT_SFENCE
    )
    io.sfence_out := sfence_fire.fire


    val src_info_queue = Module(new Queue(new DMAReadInfo, 4))
    io.src_info <> src_info_queue.io.deq

    val src_info_fire = DecoupledHelper(
        io.rocc_in.valid,
        src_info_queue.io.enq.ready,
        current_funct === FUNCT_SRC_INFO
    )

    src_info_queue.io.enq.bits.addr := io.rocc_in.bits.rs1
    src_info_queue.io.enq.bits.size := io.rocc_in.bits.rs2
    src_info_queue.io.enq.valid := src_info_fire.fire(src_info_queue.io.enq.ready)


    val dst_info_queue = Module(new Queue(new DMAWriteDstInfo, 4))
    io.dst_info <> dst_info_queue.io.deq

    val dst_info_fire = DecoupledHelper(
        io.rocc_in.valid,
        dst_info_queue.io.enq.ready,
        current_funct === FUNCT_DST_INFO
    )
    dst_info_queue.io.enq.bits.addr := io.rocc_in.bits.rs1
    // dst_info_queue.io.enq.bits.size := io.rocc_in.bits.rs2
    dst_info_queue.io.enq.valid := dst_info_fire.fire(dst_info_queue.io.enq.ready)

    // https://github.com/ucb-bar/compress-acc/blob/main/src/main/scala/ZstdMatchFinderCommandRouter.scala#L105-L112

    io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) || src_info_fire.fire(io.rocc_in.valid) || dst_info_fire.fire(io.rocc_in.valid)
}