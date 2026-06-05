package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The RFC 6455 framing peek the generated `WsFrameCodec.peekFrameSize` now
 * delegates to the `WsFrame` companion ([com.ditchoom.buffer.codec.FrameDetector]).
 * This is the gap `WsFrame.kt` previously documented as "deliberately NOT covered"
 * (the websocket repo hand-wrote it) — closed via the consumer-supplied framing
 * override rather than baking RFC 6455's escape-coded length into the processor.
 *
 * The load-bearing invariant: a peeked `Complete(n)` must equal exactly what
 * `decode` consumes, and every shorter prefix must be `NeedsMoreData`. Locked here
 * across all three length classes (7-bit indicator, 16-bit escape at 126, 64-bit
 * escape at 127), masked and unmasked — so a drift between the hand-written peek
 * and the generated decode fails loudly.
 */
class WsFramePeekCodecTest {
    private val codec = WsFrameCodec(BinaryDataCodec)

    private fun payload(size: Int): BinaryData {
        val buf = BufferFactory.Default.allocate(size)
        for (i in 0 until size) buf.writeByte((i and 0x7F).toByte())
        buf.resetForRead()
        return BinaryData(ownedBytesFrom(buf))
    }

    private fun binaryFrame(
        payloadSize: Int,
        masked: Boolean,
    ): WsFrame.Binary<BinaryData> {
        val byte2 = WsHeaderByte2.pack(payloadSize.toLong(), masked = masked)
        return WsFrame.Binary(
            byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x2),
            byte2 = byte2,
            extendedLength16 = if (byte2.extended16) payloadSize.toUShort() else null,
            extendedLength64 = if (byte2.extended64) payloadSize.toLong() else null,
            maskingKey = if (masked) WsMaskingKey(0xDEAD_BEEFu) else null,
            payload = payload(payloadSize),
        )
    }

    private fun encodeToBuffer(frame: WsFrame.Binary<BinaryData>): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(payloadFrameCapacity(frame))
        codec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return buf
    }

    private fun payloadFrameCapacity(frame: WsFrame.Binary<BinaryData>): Int =
        2 + 8 + 4 + frame.payload.size // header + max ext + mask + payload (upper bound)

    private fun decodeConsumption(frame: WsFrame.Binary<BinaryData>): Int {
        val buf = encodeToBuffer(frame)
        val total = buf.remaining()
        buf.setLimit(total)
        codec.decode(buf, DecodeContext.Empty)
        return buf.position()
    }

    @Test
    fun peekEqualsDecodeConsumptionAcrossLengthClasses() {
        // 7-bit indicator (≤125), 16-bit escape (126), 64-bit escape (127),
        // masked and unmasked. 70000 > 0xFFFF forces the 8-byte extended length.
        for (size in listOf(5, 256, 70000)) {
            for (masked in listOf(false, true)) {
                val frame = binaryFrame(size, masked)
                val total = decodeConsumption(frame)
                assertEquals(
                    PeekResult.Complete(total),
                    fullyBufferedPeek(frame),
                    "peek total == decode consumption (size=$size, masked=$masked)",
                )
                assertEquals(
                    PeekResult.NeedsMoreData,
                    prefixPeek(frame, total - 1),
                    "one byte short → NeedsMoreData (size=$size, masked=$masked)",
                )
            }
        }
    }

    @Test
    fun dripFeedNeedsMoreDataUntilLastByteForSmallFrames() {
        // Byte-at-a-time across the small length classes (full drip would be
        // wasteful at 70000 bytes; the cross-class invariant above covers those).
        assertDripPeek(binaryFrame(5, masked = false))
        assertDripPeek(binaryFrame(5, masked = true))
        assertDripPeek(binaryFrame(256, masked = false)) // 16-bit escape
        assertDripPeek(binaryFrame(256, masked = true))
    }

    private fun fullyBufferedPeek(frame: WsFrame.Binary<BinaryData>): PeekResult {
        val source = encodeToBuffer(frame)
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(source)
            return codec.peekFrameSize(stream)
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun prefixPeek(
        frame: WsFrame.Binary<BinaryData>,
        prefixLen: Int,
    ): PeekResult {
        val source = encodeToBuffer(frame)
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until prefixLen) appendByte(stream, source.readByte())
            return codec.peekFrameSize(stream)
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun assertDripPeek(frame: WsFrame.Binary<BinaryData>) {
        val pool = BufferPool()
        val source = encodeToBuffer(frame)
        val total = source.remaining()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until total - 1) {
                appendByte(stream, source.readByte())
                assertEquals(
                    PeekResult.NeedsMoreData,
                    codec.peekFrameSize(stream),
                    "after ${i + 1}/$total bytes (masked=${frame.byte2.masked})",
                )
            }
            appendByte(stream, source.readByte())
            assertEquals(
                PeekResult.Complete(total),
                codec.peekFrameSize(stream),
                "fully buffered (masked=${frame.byte2.masked})",
            )
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun appendByte(
        stream: StreamProcessor,
        byte: Byte,
    ) {
        val one: PlatformBuffer = BufferFactory.Default.allocate(1)
        one.writeByte(byte)
        one.resetForRead()
        stream.append(one)
    }
}
