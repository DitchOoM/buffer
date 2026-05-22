package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Correctness pins for the `ReadBuffer.readInto(dst, offset, length)` primitive.
 *
 * `CopyToByteArrayIndependenceTests` already exercises the `readInto` path
 * indirectly via the `copyToByteArray` default (offset=0, length=size). This
 * file covers what that test cannot:
 *   - Non-zero [offset]: writes go to the requested slot, leave the prefix alone.
 *   - Partial [length]: bytes past `offset + length` in [dst] are untouched.
 *   - Position advances by exactly [length].
 *
 * Bug class guarded: a platform `readInto` override that ignores [offset]
 * (writes from index 0 instead) or copies more bytes than requested (overruns
 * the suffix sentinel).
 */
class ReadIntoTests {
    private fun factories(): List<Pair<String, BufferFactory>> {
        val list =
            mutableListOf(
                "Default" to BufferFactory.Default,
                "managed" to BufferFactory.managed(),
            )
        if (isDeterministicAllocateSupported) {
            list += "deterministic" to BufferFactory.deterministic()
        }
        return list
    }

    @Test
    fun readIntoRespectsOffsetAndLengthLeavingSurroundingBytesUntouched() {
        for ((name, factory) in factories()) {
            val payload = ByteArray(64) { (it + 1).toByte() } // 0x01..0x40

            val src = factory.allocate(payload.size)
            src.writeBytes(payload)
            src.resetForRead()

            // Pre-fill dst with a sentinel so any out-of-window write shows up.
            val dst = ByteArray(128) { 0xAA.toByte() }

            src.readInto(dst, offset = 32, length = 64)

            // Prefix [0, 32) must still be sentinel.
            for (i in 0 until 32) {
                assertEquals(
                    0xAA.toByte(),
                    dst[i],
                    "factory=$name: dst[$i] in prefix should be untouched sentinel",
                )
            }
            // Window [32, 96) must equal the payload.
            assertContentEquals(
                payload,
                dst.copyOfRange(32, 96),
                "factory=$name: dst[32..96) should equal payload",
            )
            // Suffix [96, 128) must still be sentinel.
            for (i in 96 until 128) {
                assertEquals(
                    0xAA.toByte(),
                    dst[i],
                    "factory=$name: dst[$i] in suffix should be untouched sentinel",
                )
            }
            // Position advanced by exactly the bytes read.
            assertEquals(payload.size, src.position(), "factory=$name: position must advance by length")
        }
    }

    @Test
    fun readIntoZeroLengthIsANoOp() {
        for ((name, factory) in factories()) {
            val src = factory.allocate(16)
            src.writeBytes(ByteArray(16) { it.toByte() })
            src.resetForRead()

            val dst = ByteArray(8) { 0x55.toByte() }
            src.readInto(dst, offset = 3, length = 0)

            for (i in dst.indices) {
                assertEquals(
                    0x55.toByte(),
                    dst[i],
                    "factory=$name: dst[$i] should be unchanged for zero-length readInto",
                )
            }
            assertEquals(0, src.position(), "factory=$name: position must not advance on zero-length readInto")
        }
    }
}
