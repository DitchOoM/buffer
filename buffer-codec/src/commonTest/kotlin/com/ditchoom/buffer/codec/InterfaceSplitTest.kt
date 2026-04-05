package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.PeekResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InterfaceSplitTest {
    // ── SizeEstimate ──

    @Test
    fun sizeEstimateExactHoldsBytes() {
        assertEquals(100, SizeEstimate.Exact(100).bytes)
    }

    @Test
    fun sizeEstimateExactZero() {
        assertEquals(0, SizeEstimate.Exact(0).bytes)
    }

    @Test
    fun sizeEstimateExhaustiveWhen() {
        val estimates: List<SizeEstimate> = listOf(SizeEstimate.Exact(42), SizeEstimate.UnableToPrecalculate)
        val results =
            estimates.map { est ->
                when (est) {
                    is SizeEstimate.Exact -> est.bytes
                    SizeEstimate.UnableToPrecalculate -> -1
                }
            }
        assertEquals(listOf(42, -1), results)
    }

    // ── Decoder SAM ──

    @Test
    fun decoderSamConversion() {
        val decoder = Decoder<Int> { buffer -> buffer.readInt() }
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(42)
        buf.resetForRead()
        assertEquals(42, decoder.decode(buf))
    }

    // ── Encoder defaults ──

    @Test
    fun encoderSizeOfDefaultsToUnableToPrecalculate() {
        val encoder =
            object : Encoder<Int> {
                override fun encode(
                    buffer: WriteBuffer,
                    value: Int,
                ) {
                    buffer.writeInt(value)
                }
            }
        assertIs<SizeEstimate.UnableToPrecalculate>(encoder.sizeOf(42))
    }

    @Test
    fun encoderSizeOfCanBeOverridden() {
        val encoder =
            object : Encoder<Int> {
                override fun encode(
                    buffer: WriteBuffer,
                    value: Int,
                ) {
                    buffer.writeInt(value)
                }

                override fun sizeOf(value: Int): SizeEstimate = SizeEstimate.Exact(4)
            }
        val estimate = encoder.sizeOf(42)
        assertIs<SizeEstimate.Exact>(estimate)
        assertEquals(4, estimate.bytes)
    }

    // ── Codec: context is THE abstract method ──

    private object IntCodec : Codec<Int> {
        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): Int = buffer.readInt()

        override fun encode(
            buffer: WriteBuffer,
            value: Int,
            context: EncodeContext,
        ) {
            buffer.writeInt(value)
        }

        override fun sizeOf(value: Int): SizeEstimate = SizeEstimate.Exact(4)
    }

    @Test
    fun codecContextFreeDecodeDelgatesToContextVersion() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(99)
        buf.resetForRead()
        // Calling context-free decode — should delegate to decode(buffer, DecodeContext.Empty)
        assertEquals(99, IntCodec.decode(buf))
    }

    @Test
    fun codecContextFreeEncodeDelgatesToContextVersion() {
        val buf = BufferFactory.Default.allocate(4)
        // Calling context-free encode — should delegate to encode(buffer, value, EncodeContext.Empty)
        IntCodec.encode(buf, 77)
        buf.resetForRead()
        assertEquals(77, buf.readInt())
    }

    @Test
    fun codecPeekFrameSizeDefaultsToNeedsMoreData() {
        assertIs<PeekResult.NeedsMoreData>(IntCodec.peekFrameSize(fakeStreamProcessor(), 0))
    }

    // ── Codec is-a Encoder and Decoder ──

    @Test
    fun codecPassableAsEncoder() {
        val encoder: Encoder<Int> = IntCodec
        val buf = BufferFactory.Default.allocate(4)
        encoder.encode(buf, 55)
        buf.resetForRead()
        assertEquals(55, buf.readInt())
    }

    @Test
    fun codecPassableAsDecoder() {
        val decoder: Decoder<Int> = IntCodec
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(66)
        buf.resetForRead()
        assertEquals(66, decoder.decode(buf))
    }

    private fun fakeStreamProcessor(): com.ditchoom.buffer.stream.StreamProcessor =
        com.ditchoom.buffer.pool.withPool(defaultBufferSize = 16) { pool ->
            com.ditchoom.buffer.stream.StreamProcessor
                .create(pool)
        }
}
