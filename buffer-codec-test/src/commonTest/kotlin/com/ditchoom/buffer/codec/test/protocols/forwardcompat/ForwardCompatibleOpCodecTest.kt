package com.ditchoom.buffer.codec.test.protocols.forwardcompat

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.ForwardCompatibleFactoryKey
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `@ForwardCompatible` skip-and-preserve tests. Covers the spec's test
 * obligations: byte-identical round-trip of an unknown op, an unknown op
 * flanked by known ops with correct ordering/offsets, `peekFrameSize` on an
 * unknown op, relay identity through both `managed()` and a pooled factory,
 * and wrapper transparency (PooledBuffer / TrackedSlice).
 */
class ForwardCompatibleOpCodecTest {
    private fun readAll(buffer: ReadBuffer): ByteArray {
        val out = ByteArray(buffer.remaining())
        for (i in out.indices) out[i] = buffer.readByte()
        return out
    }

    private fun wireOf(value: ForwardCompatibleOp): ByteArray =
        readAll(
            ForwardCompatibleOpCodec.encode(
                value = value,
                context = EncodeContext.Empty,
                factory = BufferFactory.Default,
            ),
        )

    private fun bufferOf(bytes: ByteArray): PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }

    // opcode 0x99 (unknown) + varint len 3 + payload AA BB CC.
    private val unknownWire = byteArrayOf(0x99.toByte(), 0x03, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

    @Test
    fun knownVariantsStillRoundTrip() {
        val scroll = ForwardCompatibleOp.Scroll(OpCode(0x12u), delta = 0x0102)
        assertContentEquals(byteArrayOf(0x12, 0x02, 0x01, 0x02), wireOf(scroll))
        assertEquals(
            scroll,
            ForwardCompatibleOpCodec.decode(bufferOf(wireOf(scroll)), DecodeContext.Empty),
        )

        val title = ForwardCompatibleOp.SetTitle(OpCode(0x34u), title = "hi")
        assertContentEquals(byteArrayOf(0x34, 0x04, 0x00, 0x02, 0x68, 0x69), wireOf(title))
        assertEquals(
            title,
            ForwardCompatibleOpCodec.decode(bufferOf(wireOf(title)), DecodeContext.Empty),
        )
    }

    @Test
    fun unknownOpDecodesToPreservedPayload() {
        val decoded = ForwardCompatibleOpCodec.decode(bufferOf(unknownWire), DecodeContext.Empty)
        val unknown = assertIs<ForwardCompatibleOp.Unknown>(decoded)
        assertEquals(0x99, unknown.opcode)
        // raw is the payload only — no opcode, no length prefix.
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), readAll(unknown.raw.slice()))
    }

    @Test
    fun unknownOpRoundTripsByteIdentical() {
        val decoded = ForwardCompatibleOpCodec.decode(bufferOf(unknownWire), DecodeContext.Empty)
        assertContentEquals(unknownWire, wireOf(decoded))
    }

    @Test
    fun unknownOpFlankedByKnownOps() {
        val scroll = ForwardCompatibleOp.Scroll(OpCode(0x12u), delta = 0x0102)
        val title = ForwardCompatibleOp.SetTitle(OpCode(0x34u), title = "hi")
        // Concatenate three self-framed ops: known, unknown, known.
        val frame = wireOf(scroll) + unknownWire + wireOf(title)

        val buffer = bufferOf(frame)
        val decoded = mutableListOf<ForwardCompatibleOp>()
        val reencoded = ArrayList<Byte>()
        while (buffer.remaining() > 0) {
            val op = ForwardCompatibleOpCodec.decode(buffer, DecodeContext.Empty)
            decoded += op
            for (b in wireOf(op)) reencoded += b
        }

        assertEquals(3, decoded.size)
        assertEquals(scroll, decoded[0])
        val unknown = assertIs<ForwardCompatibleOp.Unknown>(decoded[1])
        assertEquals(0x99, unknown.opcode)
        assertEquals(title, decoded[2])
        // Ordering + offsets preserved: re-encoding the whole sequence
        // reproduces the original frame byte-for-byte.
        assertContentEquals(frame, reencoded.toByteArray())
    }

    @Test
    fun peekFrameSizeForUnknownOp() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(bufferOf(unknownWire))
            assertEquals(PeekResult.Complete(unknownWire.size), ForwardCompatibleOpCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeNeedsMoreDataForTruncatedUnknownOp() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // opcode + length prefix present, payload truncated to 1 of 3 bytes.
            stream.append(bufferOf(byteArrayOf(0x99.toByte(), 0x03, 0xAA.toByte())))
            assertEquals(PeekResult.NeedsMoreData, ForwardCompatibleOpCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun relayThroughManagedFactory() {
        val ctx = DecodeContext.Empty.with(ForwardCompatibleFactoryKey, BufferFactory.managed())
        val decoded = ForwardCompatibleOpCodec.decode(bufferOf(unknownWire), ctx)
        val unknown = assertIs<ForwardCompatibleOp.Unknown>(decoded)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), readAll(unknown.raw.slice()))
        assertContentEquals(unknownWire, wireOf(decoded))
    }

    @Test
    fun relayThroughPooledFactory() {
        // Inject a pool-backed BufferFactory via the context key: the
        // preserved payload is allocated from the caller's pool, and the
        // op still relays byte-identically.
        val pool = BufferPool()
        try {
            val ctx = DecodeContext.Empty.with(ForwardCompatibleFactoryKey, pool)
            val decoded = ForwardCompatibleOpCodec.decode(bufferOf(unknownWire), ctx)
            val unknown = assertIs<ForwardCompatibleOp.Unknown>(decoded)
            assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), readAll(unknown.raw.slice()))
            assertContentEquals(unknownWire, wireOf(decoded))
        } finally {
            pool.clear()
        }
    }

    @Test
    fun decodesTransparentlyThroughPooledBufferWrapper() {
        val pool = BufferPool()
        val pooled = pool.acquire(unknownWire.size)
        try {
            pooled.writeBytes(unknownWire)
            pooled.resetForRead()
            val decoded = ForwardCompatibleOpCodec.decode(pooled, DecodeContext.Empty)
            val unknown = assertIs<ForwardCompatibleOp.Unknown>(decoded)
            assertEquals(0x99, unknown.opcode)
            assertContentEquals(unknownWire, wireOf(decoded))
        } finally {
            pool.release(pooled)
            pool.clear()
        }
    }

    @Test
    fun decodesTransparentlyThroughTrackedSliceWrapper() {
        val pool = BufferPool()
        val pooled = pool.acquire(unknownWire.size)
        try {
            pooled.writeBytes(unknownWire)
            pooled.resetForRead()
            val slice = pooled.slice()
            val decoded = ForwardCompatibleOpCodec.decode(slice, DecodeContext.Empty)
            val unknown = assertIs<ForwardCompatibleOp.Unknown>(decoded)
            assertEquals(0x99, unknown.opcode)
            assertContentEquals(unknownWire, wireOf(decoded))
            (slice as? PoolReleasable)?.releaseToPool()
        } finally {
            pool.release(pooled)
            pool.clear()
        }
    }
}
