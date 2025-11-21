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
        val done = Output(Bool()) // end-of-packet (0x80)
    })

    dontTouch(io)


    // 256-bit Word -> 8-bit Stream
    // We buffer one whole 256-bit word here
    val inDataReg = Reg(UInt(256.W))
    val notdoneProcessingBeat = RegInit(false.B) // inValidReg
    val bytesProcessed    = RegInit(0.U(6.W)) // Counts 0 to 32 bytes | inCount

    // Logic to pull data from external input
    io.data_stream_in.ready := !notdoneProcessingBeat // Ready if our internal buffer is empty

    when(io.data_stream_in.fire) {
        inDataReg := io.data_stream_in.bits.data
        notdoneProcessingBeat := true.B
        bytesProcessed := 0.U
    }

    // Helper to get the current byte (Select byte based on inCount)
    // We interpret the 256-bit word as Little Endian (Byte 0 is bits 7:0)
    val currentInByte = Wire(Decoupled(UInt(8.W)))
    currentInByte.bits := (inDataReg >> (bytesProcessed * 8.U))(7, 0)
    currentInByte.valid := notdoneProcessingBeat

    // Flag to tell the FSM that a byte is ready to be consumed
    // val byteInputStreamValid = inValidReg
    // Signal from FSM to consume a byte
    // val byteInputStreamReady = Wire(Bool()) 

    // Advance input buffer when FSM consumes a byte
    // when(byteInputStreamValid && byteInputStreamReady) {
        // val nextCount = inCount + 1.U
    //     inCount := nextCount
        
    // If we read all 32 bytes (0 to 31), invalidate buffer to fetch next beat
    when((bytesProcessed + 1.U) === 32.U) {
        notdoneProcessingBeat := false.B
    }
    // }

    // output
    val outQueue = Module(new Queue(new MemLoaderConsumerBundle, 8))
    io.out <> outQueue.io.deq

    val outBuffer    = Reg(Vec(32, UInt(8.W))) // Accumulates bytes
    val outCount     = RegInit(0.U(6.W))       // Counts 0 to 32
    val flushPartial = WireInit(false.B)       // Trigger to flush incomplete word (at end)

    // Internal handshake for FSM to write specific bytes
    // val byteOutputStreamValid = Wire(Bool())
    val byteOutputStreamByte  = Wire(Decoupled(UInt(8.W)))
    // val byteOutputStreamReady = Wire(Bool())

    // Condition: We can only write if the Queue isn't full, 
    // OR if we are just filling the internal buffer and haven't hit 32 yet.
    byteOutputStreamByte.ready := (outCount < 32.U) || outQueue.io.enq.ready

    // Logic to accumulate bytes and push to Queue
    outQueue.io.enq.valid := false.B
    outQueue.io.enq.bits.data := Cat(outBuffer.reverse) // Pack Vec back to UInt
    outQueue.io.enq.bits.last := DontCare // disregard for now (TESTING, TODO)

    when(byteOutputStreamByte.fire) {
        outBuffer(outCount) := byteOutputStreamByte.bits
        outCount := outCount + 1.U

        // If we just filled the last byte (idx 31 -> count 32), push to queue
        when(outCount === 31.U) {
            outQueue.io.enq.valid := true.B
            // Reset count after push
            outCount := 0.U

            PackBitsAccLogger.logInfo("[PackBitsDecompressModule] Pushing to out queue: 0x%x\n", Cat(outBuffer.reverse))
        }
    }

    // Handle Flush (End of Packet): Push whatever we have, even if < 32 bytes
    when(flushPartial && outCount > 0.U) {
        outQueue.io.enq.valid := true.B
        outCount := 0.U // Clear buffer
        // Note: standard packbits doesn't specify padding, remaining bytes in `outBuffer` 
        // will be whatever was there before (or 0 if reset). 
    }


    // CORE FSM (PackBits Logic)
    val sIdle :: sLiteral :: sReadRep :: sReplicate :: sDone :: Nil = Enum(5)
    val state = RegInit(sIdle)

    val counter = Reg(UInt(9.W))
    val repVal  = Reg(UInt(8.W))

    // Default assignments
    currentInByte.ready  := false.B
    byteOutputStreamByte.valid := false.B
    byteOutputStreamByte.bits  := DontCare
    io.done := (state === sDone)

    switch(state) {
        // ------------------------------------------------------------
        // STATE: IDLE - Read Header
        // ------------------------------------------------------------
        is(sIdle) {
        currentInByte.ready := true.B // We want to read a header


        when(currentInByte.fire) {
            // val header = currentInByte
            val isNegative = currentInByte.bits(7)

            when(currentInByte.bits === 128.U) {
            // End of Packet Marker (0x80)
            state := sDone
            flushPartial := true.B // Flush any remaining output bytes
            } .elsewhen(!isNegative) {
            // Literal Run (0 to 127) -> Copy (Header + 1) bytes
            counter := currentInByte.bits +& 1.U
            bytesProcessed := bytesProcessed + 1.U
            state   := sLiteral
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
        // We need Input Valid AND Output Ready
        // val handshake = byteInputStreamValid && byteOutputStreamReady
        
        // byteInputStreamReady  := handshake
        // byteOutputStreamValid := handshake

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
            state  := sReplicate
        }
        }

        // ------------------------------------------------------------
        // STATE: REPLICATE - Write that 1 byte N times
        // ------------------------------------------------------------
        is(sReplicate) {
        // We only need Output Ready (not reading input)
        currentInByte.ready := false.B
        // byteOutputStreamValid := true.B
        byteOutputStreamByte.valid := true.B
        byteOutputStreamByte.bits  := repVal

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
        // Dead state until Reset. 
        // Logic ensures io.done is high.
        currentInByte.ready := false.B
        }
    }

}