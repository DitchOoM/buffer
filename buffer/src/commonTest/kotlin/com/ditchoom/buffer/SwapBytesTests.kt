package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pinning tests for [swapBytes]. The implementation delegates to
 * Kotlin stdlib's `reverseBytes()` which compiles to a platform
 * intrinsic on JVM and Native and shift/or on JS/WASM. These tests
 * verify the contract: bit pattern reversed bytewise, sign-bit
 * positions preserved across the swap.
 *
 * Run on every KMP target. A regression here would silently break
 * every batched codec with explicit Big or Little wire order — the
 * canonicalizing branch in generated code reads
 * `if (buffer.byteOrder == X) raw else swapBytes(raw)`.
 */
class SwapBytesTests {
    @Test
    fun short_reverses_bytewise() {
        assertEquals(0x3412.toShort(), swapBytes(0x1234.toShort()))
        assertEquals(0x0100.toShort(), swapBytes(0x0001.toShort()))
        assertEquals(0xFF00.toShort(), swapBytes(0x00FF.toShort()))
    }

    @Test
    fun short_round_trip_is_identity() {
        for (v in shortArrayOf(0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE, 0x1234, 0x7FFF.toShort())) {
            assertEquals(v, swapBytes(swapBytes(v)), "round-trip for $v")
        }
    }

    @Test
    fun int_reverses_bytewise() {
        assertEquals(0x78563412, swapBytes(0x12345678))
        assertEquals(0x01000000, swapBytes(0x00000001))
        assertEquals(0xFFFFFFFF.toInt(), swapBytes(0xFFFFFFFF.toInt()))
    }

    @Test
    fun int_round_trip_is_identity() {
        for (v in intArrayOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x12345678, 0x7FFFFFFF, 0xDEADBEEF.toInt())) {
            assertEquals(v, swapBytes(swapBytes(v)), "round-trip for $v")
        }
    }

    @Test
    fun long_reverses_bytewise() {
        assertEquals(0xF0DEBC9A78563412uL.toLong(), swapBytes(0x123456789ABCDEF0uL.toLong()))
        assertEquals(0x0100000000000000L, swapBytes(0x0000000000000001L))
    }

    @Test
    fun long_round_trip_is_identity() {
        for (v in longArrayOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789ABCDEF0L, 0x7FFFFFFFFFFFFFFFL)) {
            assertEquals(v, swapBytes(swapBytes(v)), "round-trip for $v")
        }
    }

    @Test
    fun matches_manual_shift_or_for_known_values() {
        // Manual shift/or reference. If a platform intrinsic ever
        // diverges from this on a specific value, this test pins the
        // discrepancy at swapBytes rather than at the codec layer.
        for (v in intArrayOf(0x00000001, 0x00010000, 0x12345678, 0xCAFEBABE.toInt(), Int.MIN_VALUE)) {
            val manual =
                ((v and 0xFF) shl 24) or
                    (((v ushr 8) and 0xFF) shl 16) or
                    (((v ushr 16) and 0xFF) shl 8) or
                    ((v ushr 24) and 0xFF)
            assertEquals(manual, swapBytes(v), "value 0x${v.toUInt().toString(16)}")
        }
    }
}
