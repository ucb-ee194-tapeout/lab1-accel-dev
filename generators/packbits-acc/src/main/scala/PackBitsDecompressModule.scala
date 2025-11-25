package packbitsacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.util.DontTouch


class PackBitsDecompressModule()(implicit p: Parameters) extends Module {

    val io = IO(new Bundle {
        val data_stream_in = Flipped(Decoupled(new MemLoaderConsumerBundle))
        val out = Decoupled(new MemLoaderConsumerBundle)
        val done = Output(Bool())
    })

    // 256-bit Word -> 8-bit Stream
    // We buffer one whole 256-bit word here
    val inDataReg = Reg(UInt(256.W))
    val lastBeatReg = RegInit(false.B) // is this the last beat of data_stream_in?
    val seenLastCompressedByte = RegInit(false.B)
    val notdoneProcessingBeat = RegInit(false.B) // inValidReg
    val bytesProcessed = RegInit(0.U(6.W)) // Counts 0 to 32 bytes | inCount

    // Logic to pull data from external input
    io.data_stream_in.ready := !notdoneProcessingBeat && !seenLastCompressedByte // Ready if our internal buffer is empty

    when(io.data_stream_in.fire) {
        inDataReg := io.data_stream_in.bits.data
        notdoneProcessingBeat := true.B
        bytesProcessed := 0.U
        lastBeatReg := io.data_stream_in.bits.last
    }

    // get the current byte (Select byte based on inCount)
    // riscv is Little Endian (Byte 0 is bits 7:0)
    val currentInByte = Wire(Decoupled(UInt(8.W)))
    currentInByte.bits := (inDataReg >> (bytesProcessed * 8.U))(7, 0)
    currentInByte.valid := notdoneProcessingBeat

    // output
    val outQueue = Module(new Queue(new MemLoaderConsumerBundle, 8))
    io.out <> outQueue.io.deq

    val outBuffer = Reg(Vec(32, UInt(8.W))) // Accumulates bytes
    val outCount = RegInit(0.U(6.W)) // Counts 0 to 32

    // Internal handshake for FSM to write specific bytes
    val byteOutputStreamByte  = Wire(Decoupled(UInt(8.W)))

    // We can only write if the Queue isn't full, 
    // OR if we are just filling the internal buffer and haven't hit 32 yet.
    byteOutputStreamByte.ready := (outCount < 32.U) || outQueue.io.enq.ready

    // Output: accumulate bytes and push to output Queue (outQueue)
    outQueue.io.enq.valid := false.B
    outQueue.io.enq.bits.data := Cat(outBuffer.reverse) // Pack Vec back to UInt

    when(byteOutputStreamByte.fire) {
        outBuffer(outCount) := byteOutputStreamByte.bits
        PackBitsAccLogger.logInfo("[PackBitsDecompressModule] byteOutputStreamByte.fire -- outCount: 0x%x\n", outCount)

        // If we just filled the last byte, push to queue
        when((outCount + 1.U) === 32.U) {
            outQueue.io.enq.valid := true.B

            val temp = Wire(Vec(32, UInt(8.W))) // wire here cause reg will only update on the next cycle. we want to push to output now
            temp := outBuffer
            temp(outCount) := byteOutputStreamByte.bits  // override index 31 with the new value
            outQueue.io.enq.bits.data := Cat(temp.reverse) // Pack Vec back to UInt

            // Reset count after push
            outCount := 0.U

            PackBitsAccLogger.logInfo("[PackBitsDecompressModule] Pushing to out queue: 0x%x | last: %x\n", outQueue.io.enq.bits.data, outQueue.io.enq.bits.last)
        } .otherwise {
            outCount := outCount + 1.U
        }
    }

    // CORE FSM (PackBits Logic)
    val sIdle :: sLiteral :: sReadRep :: sReplicate :: sDone :: Nil = Enum(5)
    val state = RegInit(sIdle)

    val counter = Reg(UInt(9.W))
    val repVal  = Reg(UInt(8.W))

    // Default assignments
    currentInByte.ready := false.B
    byteOutputStreamByte.valid := false.B
    byteOutputStreamByte.bits := DontCare
    io.done := (state === sDone)

    switch(state) {
        // ------------------------------------------------------------
        // STATE: IDLE - Read Header
        // ------------------------------------------------------------
        is(sIdle) {
        currentInByte.ready := true.B // ok to accept data
        byteOutputStreamByte.valid := false.B

            when(currentInByte.fire) {
                // everything here is fully unsigned; however, incoming data can be negative, so we need to perform 2's complement math in this fsm
                val isNegative = currentInByte.bits(7)

                when(!isNegative) {
                    // Literal Run (0 to 127) -> Copy (Header + 1) bytes
                    counter := currentInByte.bits +& 1.U
                    bytesProcessed := bytesProcessed + 1.U
                    state := sLiteral
                } .otherwise {
                    // Replicate Run (-1 to -127) -> Repeat byte (1 - Header) times
                    // 2's complement math: (0 - header) + 1
                    counter := (0.U - currentInByte.bits) +& 1.U
                    bytesProcessed := bytesProcessed + 1.U
                    state   := sReadRep
                }
            }
        }

        // ------------------------------------------------------------
        // STATE: LITERAL - Copy bytes 1-to-1
        // ------------------------------------------------------------
        is(sLiteral) {
            val copy_fire = DecoupledHelper(
                currentInByte.valid,
                byteOutputStreamByte.ready
            )

            byteOutputStreamByte.bits := currentInByte.bits
            byteOutputStreamByte.valid := copy_fire.fire(byteOutputStreamByte.ready)

            currentInByte.ready := copy_fire.fire(currentInByte.valid)

            when(copy_fire.fire()) {
                bytesProcessed := bytesProcessed + 1.U
                counter := counter - 1.U
                when((counter - 1.U) === 0.U) {
                    state := sIdle
                }
            }
        }

        // ------------------------------------------------------------
        // STATE: READ REPLICATE VALUE - Read the 1 byte to repeat
        // ------------------------------------------------------------
        is(sReadRep) {
            currentInByte.ready := true.B
            when(currentInByte.fire) {
                repVal := currentInByte.bits
                state := sReplicate
            }
        }

        // ------------------------------------------------------------
        // STATE: REPLICATE - Write that 1 byte N times
        // ------------------------------------------------------------
        is(sReplicate) {
            // We only need Output Ready (not reading input)
            currentInByte.ready := false.B
            byteOutputStreamByte.valid := true.B
            byteOutputStreamByte.bits := repVal

            when(byteOutputStreamByte.fire) {
                counter := counter - 1.U
                when((counter - 1.U) === 0.U) {
                    bytesProcessed := bytesProcessed + 1.U
                    state := sIdle
                }
            }
        }

        // ------------------------------------------------------------
        // STATE: DONE
        // ------------------------------------------------------------
        is(sDone) {
            // Dead state until Reset. We only support one activation of the accelerator for now as reset logic isn't implemented.
            byteOutputStreamByte.valid := false.B
            seenLastCompressedByte := true.B
            notdoneProcessingBeat := false.B
            outQueue.io.enq.valid := false.B
            PackBitsAccLogger.logInfo("[PackBitsDecompressModule] State: DONE\n")
        }
    }

    // If we read all 32 bytes (0 to 31), check we didnt' just read last beat ... finish up if 
    // outcount >= 32.U to let it finish up
     when (bytesProcessed >= 31.U && lastBeatReg && state === sIdle && outCount >= 32.U) {
        state := sDone
        // last manual push out to queue
        outQueue.io.enq.valid := true.B
        PackBitsAccLogger.logInfo("[PackBitsDecompressModule] (LAST) Pushing to out queue: 0x%x | last: %x\n", outQueue.io.enq.bits.data, outQueue.io.enq.bits.last)
    }

    when (bytesProcessed >= 31.U && !lastBeatReg && state === sIdle) {
        notdoneProcessingBeat := false.B
    } 

    outQueue.io.enq.bits.last := (state === sDone)
}