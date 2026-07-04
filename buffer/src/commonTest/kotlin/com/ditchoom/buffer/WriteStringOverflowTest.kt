package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression tests for `writeString` overflow handling (issue #250).
 *
 * `writeString` must throw [BufferOverflowException] when the encoded bytes
 * exceed the write window, rather than:
 *   - silently truncating the output (JVM `CharsetEncoder` OVERFLOW result was
 *     discarded; JS `TextEncoder.encodeInto` truncates to the target size), or
 *   - overrunning native memory (Linux `simdutf` wrote the full multi-byte
 *     UTF-8 with no destination cap after a char-count-only bounds check).
 *
 * Runs on every platform so all `writeString` actuals are held to the contract.
 */
class WriteStringOverflowTest {
    private fun factories(): List<Pair<String, BufferFactory>> =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
        )

    @Test
    fun writeStringThrowsWhenAsciiExceedsCapacity() {
        for ((name, factory) in factories()) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("[$name] ascii overflow must throw") {
                buffer.writeString("abcdefgh") // 8 ASCII bytes into a 4-byte buffer
            }
        }
    }

    @Test
    fun writeStringThrowsWhenMultiByteExceedsCapacity() {
        // Each 'é' encodes to 2 UTF-8 bytes → 10 bytes needed, only 4 available.
        // The pre-fix native path overran the buffer instead of throwing because
        // it checked only the char count (5), not the encoded length (10).
        for ((name, factory) in factories()) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("[$name] multibyte overflow must throw") {
                buffer.writeString("ééééé")
            }
        }
    }

    @Test
    fun writeStringThrowsWhenAppendingPastRemaining() {
        // Partially fill, then overflow the remaining window — the silent-truncation case.
        for ((name, factory) in factories()) {
            val buffer = factory.allocate(8)
            buffer.writeString("123456") // 6 bytes, remaining = 2
            assertFailsWith<BufferOverflowException>("[$name] short write past remaining must throw") {
                buffer.writeString("789") // 3 bytes into 2 remaining
            }
        }
    }

    @Test
    fun writeStringSucceedsWhenExactlyFits() {
        for ((name, factory) in factories()) {
            val buffer = factory.allocate(5)
            buffer.writeString("hello") // exactly 5 UTF-8 bytes
            buffer.resetForRead()
            assertEquals(5, buffer.remaining(), "[$name] full string must be written")
            assertEquals("hello", buffer.readString(5, Charset.UTF8).toString(), "[$name] round-trip")
        }
    }
}
