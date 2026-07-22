package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Hostile-length guard for nested narrowing `setLimit` — a codec running
 * under an outer narrowed limit must never WIDEN `buffer.limit()` from a
 * lying wire-supplied length. `ReadBuffer.setLimit` widens unchecked on
 * every platform, so without a decode-side guard an inner region can
 * silently read adjacent bytes past its enclosing bound and still pass
 * strict-consumption (it consumed exactly the lying region). Same bug
 * class as the encode-side/peek fixes in #306/#309.
 *
 * [TlsRecord] is the end-to-end vector: the record's `@LengthPrefixed`
 * bound narrows the buffer, then the nested [TlsHandshake]'s
 * `@LengthFrom("length")` uint24 lies past it.
 */
class TlsRecordWidenGuardTest {
    @Test
    fun recordWithHonestNestedHandshakeRoundTrips() {
        val body =
            TlsHandshakeBody(
                legacyVersion = 0x0303u,
                random = BinaryData(byteArrayOf(0x0A, 0x0B, 0x0C)),
            )
        // body = 2 (legacyVersion) + 3 (random) = 5
        val original =
            TlsRecord(
                contentType = 0x16u,
                legacyRecordVersion = 0x0303u,
                fragment = TlsHandshake(msgType = 0x01u, length = 5u, body = body),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        TlsRecordCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = TlsRecordCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original.contentType, decoded.contentType)
        assertEquals(original.fragment.length, decoded.fragment.length)
        assertEquals(original.fragment.body.legacyVersion, decoded.fragment.body.legacyVersion)
    }

    /**
     * The inner handshake's uint24 claims 100 body bytes but the record's
     * own bound only leaves 3. Widening would silently swallow the bytes
     * of whatever follows the record in the same buffer.
     */
    @Test
    fun lyingInnerLengthCannotWidenPastTheRecordBound() {
        val buf = hostileRecord()
        val failure =
            assertFailsWith<DecodeException> {
                TlsRecordCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("TlsHandshake.body", failure.fieldPath)
        assertContains(failure.actual, "3 bytes")
    }

    /** The guard must not disturb the outer restore: limit is back at the caller's bound. */
    @Test
    fun outerLimitSurvivesTheRejectedDecode() {
        val buf = hostileRecord()
        val outerLimit = buf.limit()
        assertFailsWith<DecodeException> { TlsRecordCodec.decode(buf, DecodeContext.Empty) }
        assertEquals(outerLimit, buf.limit(), "outer limit restored by the try/finally chain")
    }

    /**
     * The record's own `@LengthPrefixed` prefix lying past a caller-narrowed
     * limit is the same hole one level up ("@LengthPrefixed message bodies").
     */
    @Test
    fun lyingRecordPrefixUnderNarrowedLimitIsRejected() {
        val buf = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        buf.writeUByte(0x16u) // contentType
        buf.writeUShort(0x0303u) // legacyRecordVersion
        buf.writeUShort(200u) // fragment prefix LIES: the carve below only leaves 7
        writeHonestFragment(buf, bodyByteCount = 3)
        val recordEnd = buf.position()
        repeat(220) { buf.writeByte(0x5A) } // adjacent bytes owned by the next record
        buf.setLimit(buf.position())
        buf.position(0)
        buf.setLimit(recordEnd) // outer carve, e.g. a record-layer dispatcher

        val failure =
            assertFailsWith<DecodeException> {
                TlsRecordCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("TlsRecord.fragment", failure.fieldPath)
    }

    /**
     * One record whose inner uint24 claims 100 bytes, followed by adjacent
     * bytes that a widened limit would expose. The record itself is
     * well-formed at the envelope layer: prefix = 7 = actual fragment bytes.
     */
    private fun hostileRecord(): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        buf.writeUByte(0x16u) // contentType
        buf.writeUShort(0x0303u) // legacyRecordVersion
        buf.writeUShort(7u) // fragment prefix: honest (1 msgType + 3 length + 3 body)
        writeHonestFragment(buf, bodyByteCount = 3, lyingLength = 100u)
        repeat(200) { buf.writeByte(0x5A) } // bytes past the record a widen would read
        buf.setLimit(buf.position())
        buf.position(0)
        return buf
    }

    private fun writeHonestFragment(
        buf: PlatformBuffer,
        bodyByteCount: Int,
        lyingLength: UInt? = null,
    ) {
        buf.writeUByte(0x01u) // msgType
        val declared = lyingLength ?: bodyByteCount.toUInt()
        buf.writeUByte(((declared shr 16) and 0xFFu).toUByte()) // uint24 length
        buf.writeUByte(((declared shr 8) and 0xFFu).toUByte())
        buf.writeUByte((declared and 0xFFu).toUByte())
        buf.writeUShort(0x0303u) // body.legacyVersion
        repeat(bodyByteCount - 2) { buf.writeByte(0x42) } // body.random
    }
}
