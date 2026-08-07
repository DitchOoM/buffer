@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import kotlinx.cinterop.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val WINDOW_BYTES = 16

/** A window starting here and running [TAIL_BYTES] long is the last valid one. */
private const val TAIL_BYTES = 4

/** Twice [TAIL_BYTES], so a window starting at the tail runs off the end. */
private const val OVERSHOOT_BYTES = 8

/**
 * `withBytesAt` replaced a `slice()`-based view with raw pointer arithmetic (#332). The view it
 * replaced validated its window for free — `setLimit(from + count)` threw before any pointer
 * existed — so these pin the explicit bounds check that took its place. Without it an out-of-range
 * `from` becomes `(nativeAddress + from).toCPointer()`, a wild pointer handed to CommonCrypto, on
 * the native backing where nothing else would catch it.
 *
 * Both backings are covered because they fail differently when unchecked: the managed branch
 * indexes a Kotlin array (throws, late), the native branch does not (silent out-of-bounds read).
 */
class AppleCryptoBridgeWindowTest {
    private fun backings() =
        listOf(
            "native" to BufferFactory.Default.allocate(WINDOW_BYTES),
            "managed" to BufferFactory.managed().allocate(WINDOW_BYTES),
        ).map { (name, buffer) ->
            repeat(WINDOW_BYTES) { buffer.writeByte(it.toByte()) }
            buffer.resetForRead()
            name to buffer
        }

    @Test
    fun windowPastTheLimitIsRejected() {
        backings().forEach { (name, buffer) ->
            assertFailsWith<IllegalArgumentException>("$name: from+count past limit must be rejected") {
                buffer.withBytesAt(WINDOW_BYTES - TAIL_BYTES, OVERSHOOT_BYTES) { }
            }
        }
    }

    @Test
    fun startPastTheLimitIsRejected() {
        backings().forEach { (name, buffer) ->
            assertFailsWith<IllegalArgumentException>("$name: from past limit must be rejected") {
                buffer.withBytesAt(WINDOW_BYTES + 1, 0) { }
            }
        }
    }

    @Test
    fun negativeWindowIsRejected() {
        backings().forEach { (name, buffer) ->
            assertFailsWith<IllegalArgumentException>("$name: negative from must be rejected") {
                buffer.withBytesAt(-1, TAIL_BYTES) { }
            }
            assertFailsWith<IllegalArgumentException>("$name: negative count must be rejected") {
                buffer.withBytesAt(0, -1) { }
            }
        }
    }

    /**
     * `from + count` must not be allowed to overflow past the check — hence `count <= limit() - from`
     * rather than `from + count <= limit()`.
     */
    @Test
    fun overflowingWindowIsRejected() {
        backings().forEach { (name, buffer) ->
            assertFailsWith<IllegalArgumentException>("$name: overflowing window must be rejected") {
                buffer.withBytesAt(WINDOW_BYTES, Int.MAX_VALUE) { }
            }
        }
    }

    @Test
    fun windowInsideTheLimitStillReads() {
        backings().forEach { (name, buffer) ->
            var seen = -1
            buffer.withBytesAt(WINDOW_BYTES - TAIL_BYTES, TAIL_BYTES) { ptr -> seen = ptr[0].toInt() }
            assertEquals(WINDOW_BYTES - TAIL_BYTES, seen, "$name: a valid window must still resolve")
        }
    }
}
