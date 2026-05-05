package com.ditchoom.buffer.codec.test.protocols.slice14a

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase J.M.5 slice 14a — `@DerivedLength` capability probe.
 *
 * Three checks:
 *  1. **Default-construction encode succeeds** — caller omits `length`,
 *     the default `3u` matches the framework-derived value, encode
 *     writes `03 PP HH LL`.
 *  2. **Round-trip preserves the length field** — decode reads `03`
 *     into `length: UInt`, the data class compares equal to the
 *     original.
 *  3. **Mismatched-length encode throws** — caller passes
 *     `length = 99u`; encode throws `EncodeException` naming the
 *     framework-derived value.
 */
class Slice14aProbeCodecTest {
    @Test
    fun defaultLengthRoundTrips() {
        val original =
            Slice14aDerivedFrame(
                payload = 0x42u,
                tail = 0xABCDu,
            )
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        Slice14aDerivedFrameCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val bytes = ByteArray(buf.remaining())
        for (i in bytes.indices) bytes[i] = buf.readByte()
        assertContentEquals(byteArrayOf(0x03, 0x42, 0xAB.toByte(), 0xCD.toByte()), bytes)

        // Round-trip via decode against the same buffer.
        val readBuf = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        readBuf.writeBytes(bytes)
        readBuf.resetForRead()
        val decoded = Slice14aDerivedFrameCodec.decode(readBuf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun explicitMatchingLengthRoundTrips() {
        // Caller supplies the matching value explicitly — equally valid.
        val original =
            Slice14aDerivedFrame(
                length = 3u,
                payload = 0x55u,
                tail = 0x1234u,
            )
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        Slice14aDerivedFrameCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = Slice14aDerivedFrameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun mismatchedLengthThrows() {
        val bad =
            Slice14aDerivedFrame(
                length = 99u,
                payload = 0x42u,
                tail = 0x0001u,
            )
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        val ex =
            assertFailsWith<EncodeException> {
                Slice14aDerivedFrameCodec.encode(buf, bad, EncodeContext.Empty)
            }
        assertTrue(
            ex.message!!.contains("@DerivedLength"),
            "expected @DerivedLength diagnostic, got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("99"),
            "expected diagnostic to name caller-supplied value, got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("3"),
            "expected diagnostic to name framework-derived value, got: ${ex.message}",
        )
    }
}
