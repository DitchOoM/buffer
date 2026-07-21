package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.MaxFrameBytesKey
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.readFrame
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `peekFrameSize` reads a wire-supplied length before any decode runs, so a
 * hostile peer controls its arithmetic, and `readFrame` decides how many bytes
 * a streaming loop will buffer on the strength of that number.
 *
 * [WideLengthFrame]'s full-width `UInt` carrier is the shape that reaches both
 * limits: a length near `Int.MAX_VALUE` overflows the peek walk's running
 * offset, and a merely-large one drives unbounded accumulation.
 */
class WideLengthFramePeekTest {
    private fun streamOf(bytes: ByteArray): StreamProcessor {
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        val buf: PlatformBuffer = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        stream.append(buf)
        return stream
    }

    /** `length` big-endian, then `flags`, then [body] as the payload bytes. */
    private fun frameBytes(
        length: UInt,
        body: ByteArray = ByteArray(0),
    ): ByteArray =
        byteArrayOf(
            (length shr 24).toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte(),
            0x00,
        ) + body

    @Test
    fun peekRejectsLengthThatOverflowsTheRunningOffset() {
        // 0x7FFFFFFF passes the emitted `> Int.MAX_VALUE` guard on its own, but
        // the walk has already advanced 5 bytes: `__offset + payloadBytes` wraps
        // negative, and a negative `Complete` reaches `readBufferScoped`.
        val stream = streamOf(frameBytes(0x7FFFFFFFu))
        try {
            assertFailsWith<DecodeException> { WideLengthFrameCodec.peekFrameSize(stream) }
        } finally {
            stream.release()
        }
    }

    @Test
    fun peekReportsCompleteForAWellFormedFrame() {
        val payload = "hi".encodeToByteArray()
        val stream = streamOf(frameBytes(payload.size.toUInt(), payload))
        try {
            assertEquals(PeekResult.Complete(5 + payload.size), WideLengthFrameCodec.peekFrameSize(stream))
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameDoesNotYetBoundPreArrivalAccumulation() {
        // Known gap, tracked in issue #308. The peek walk computes 8 MiB and then
        // discards it — `NeedsMoreData` cannot say "known, but unsatisfied" — so
        // `readFrame` never sees a `Complete` to check the ceiling against and
        // returns null. A caller looping on that null keeps appending transport
        // bytes for a frame the peer never intends to send. Closing this needs
        // `PeekResult.Incomplete(requiredBytes)`, a v7 change.
        val stream = streamOf(frameBytes(8u * 1024u * 1024u))
        try {
            assertNull(WideLengthFrameCodec.readFrame(stream))
        } finally {
            stream.release()
        }
    }

    @Test
    fun maxFrameBytesKeyOverridesTheDefaultCeiling() {
        val payload = "hi".encodeToByteArray()
        val frame = frameBytes(payload.size.toUInt(), payload)
        val lowered = DecodeContext.Empty.with(MaxFrameBytesKey, 4)
        val tooSmall = streamOf(frame)
        try {
            val thrown =
                assertFailsWith<FrameTooLargeException> {
                    WideLengthFrameCodec.readFrame(tooSmall, lowered)
                }
            assertEquals(4, thrown.maxBytes)
        } finally {
            tooSmall.release()
        }
        // The same frame decodes once the ceiling admits it.
        val raised = streamOf(frame)
        try {
            val decoded = WideLengthFrameCodec.readFrame(raised, DecodeContext.Empty.with(MaxFrameBytesKey, 64))
            assertEquals("hi", decoded?.payload)
        } finally {
            raised.release()
        }
    }

    @Test
    fun readFrameWaitsForAnIncompleteFrameUnderTheCeiling() {
        // Declared size is legal; the body simply has not arrived yet. This must
        // stay `null` ("append more bytes and retry"), not throw.
        val stream = streamOf(frameBytes(64u))
        try {
            assertNull(WideLengthFrameCodec.readFrame(stream))
        } finally {
            stream.release()
        }
    }

    @Test
    fun roundTripsThroughTheStreamingLoop() {
        val value = WideLengthFrame(length = 2u, flags = 0x07u, payload = "hi")
        val encoded = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        WideLengthFrameCodec.encode(encoded, value, EncodeContext.Empty)
        encoded.resetForRead()
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        try {
            // Byte at a time: every prefix must wait, only the last byte completes.
            val total = encoded.remaining()
            for (i in 0 until total) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                val decoded = WideLengthFrameCodec.readFrame(stream)
                if (i < total - 1) {
                    assertNull(decoded, "frame completed early after ${i + 1}/$total bytes")
                } else {
                    assertEquals(value, decoded)
                }
            }
            assertTrue(stream.available() == 0, "the completed frame was consumed")
        } finally {
            stream.release()
        }
    }
}
