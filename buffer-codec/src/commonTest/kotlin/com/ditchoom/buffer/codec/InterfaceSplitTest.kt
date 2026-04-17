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
    // ── Decoder SAM ──

    @Test
    fun decoderSamConversion() {
        val decoder = Decoder<Int> { buffer -> buffer.readInt() }
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(42)
        buf.resetForRead()
        assertEquals(42, decoder.decode(buf))
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
    fun encoderEncodeToBufferWithoutContext() {
        // Encoder.encodeToBuffer works without context for plain encoders.
        val encoder: Encoder<Int> = IntCodec
        val encoded = encoder.encodeToBuffer(77)
        assertEquals(4, encoded.remaining())
        assertEquals(77, encoded.readInt())
    }

    @Test
    fun encoderEncodeToBufferForwardsContextWhenCodec() {
        // When an Encoder reference is actually a Codec at runtime,
        // encodeToBuffer must forward context to the context-aware encode method.
        val key = object : CodecContext.Key<String>() {}
        var receivedContext: EncodeContext = EncodeContext.Empty
        val codec =
            object : Codec<Int> {
                override fun decode(
                    buffer: ReadBuffer,
                    context: DecodeContext,
                ): Int = buffer.readInt()

                override fun encode(
                    buffer: WriteBuffer,
                    value: Int,
                    context: EncodeContext,
                ) {
                    receivedContext = context
                    buffer.writeInt(value)
                }
            }
        // Call through an Encoder reference — context must still arrive
        val encoder: Encoder<Int> = codec
        val ctx = EncodeContext.Empty.with(key, "hello")
        val encoded = encoder.encodeToBuffer(42, context = ctx)
        assertEquals(4, encoded.remaining())
        assertEquals(42, encoded.readInt())
        assertEquals("hello", receivedContext[key])
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
