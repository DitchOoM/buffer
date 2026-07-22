package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.readFrame
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `@LengthFrom` peek overflow guard, executed on a *deferred* payload.
 *
 * Deferred peeks reuse `appendSequentialPeekLengthFrom`, so they inherit the
 * guard added for the `String` shape ([com.ditchoom.buffer.codec.test.protocols.simple.WideLengthFramePeekTest]
 * covers that one) — but inheritance is only proven by execution. This drives
 * [WideLengthDeferredFrame]'s full-width carrier past `Int.MAX_VALUE - __offset`
 * and asserts the peek rejects it instead of returning a negative `Complete`.
 */
class WideLengthDeferredFramePeekTest {
    /** `payloadLength` big-endian, then `flags` — the 5 fixed header bytes. */
    private fun headerOf(length: UInt): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        buf.writeUInt(length)
        buf.writeUByte(0u)
        return buf
    }

    private fun streamOf(buf: PlatformBuffer): StreamProcessor {
        buf.resetForRead()
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        stream.append(buf)
        return stream
    }

    @Test
    fun peekRejectsLengthThatOverflowsTheRunningOffset() {
        // Int.MAX_VALUE passes the sibling's own `> Int.MAX_VALUE` bound, but
        // the walk has already advanced 5 bytes: `__offset + payloadBytes`
        // wraps negative, and without the guard that negative `Complete`
        // reaches `readBufferScoped`.
        val stream = streamOf(headerOf(0x7FFFFFFFu))
        try {
            assertFailsWith<DecodeException> { WideLengthDeferredFrameCodec.peekFrameSize(stream) }
        } finally {
            stream.release()
        }
    }

    @Test
    fun peekReportsCompleteForAWellFormedFrame() {
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeUInt(2u)
        buf.writeUByte(0u)
        buf.writeString("hi")
        val stream = streamOf(buf)
        try {
            assertEquals(PeekResult.Complete(7), WideLengthDeferredFrameCodec.peekFrameSize(stream))
        } finally {
            stream.release()
        }
    }

    @Test
    fun roundTripsThroughReadFrame() {
        val value =
            WideLengthDeferredFrame(
                payloadLength = 2u,
                flags = 0x07u,
                payload = TextPayload("hi"),
            )
        val encoded = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        WideLengthDeferredFrameCodec.encode(encoded, value, EncodeContext.Empty)
        encoded.resetForRead()
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        try {
            stream.append(encoded)
            assertEquals(value, WideLengthDeferredFrameCodec.readFrame(stream))
        } finally {
            stream.release()
        }
    }
}
