package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the aliasing contract of [asReadBuffer] and [com.ditchoom.buffer.ReadBuffer.slice], because
 * the difference between them decides whether a multi-consumer read is correct.
 *
 * `asReadBuffer()` reads like a per-consumer view mint. It is not one: every actual returns the
 * handle's own buffer after rewinding it, so all calls hand back the same instance. That is easy to
 * rely on incorrectly — a fan-out giving each of N consumers its own `asReadBuffer()` call looks
 * right and silently has them share one cursor.
 */
class OwnedBytesHandleAliasingTest {
    private fun handleOf(size: Int = 8): OwnedBytesHandle {
        val buffer = BufferFactory.Default.allocate(size, ByteOrder.BIG_ENDIAN)
        buffer.write(ByteArray(size) { it.toByte() })
        buffer.resetForRead()
        return ownedBytesFrom(buffer)
    }

    @Test
    fun asReadBufferRewindsTheOneBufferItKeepsHandingOut() {
        val handle = handleOf()

        val first = handle.asReadBuffer()
        assertEquals(8, first.remaining())
        first.readByteArray(8)
        assertEquals(0, first.remaining())

        // Documented: a later call rewinds to position 0...
        val second = handle.asReadBuffer()
        assertEquals(8, second.remaining())

        // ...and the part that is easy to miss — it rewound `first` too, because `first` and
        // `second` are the same buffer. A reader mid-way through its bytes is silently reset.
        assertEquals(8, first.remaining())
    }

    @Test
    fun sliceGivesEachReaderItsOwnPosition() {
        val handle = handleOf()
        val base = handle.asReadBuffer()

        val first = base.slice()
        val second = base.slice()
        assertEquals(8, first.remaining())
        assertEquals(8, second.remaining())

        first.readByteArray(8)

        // Slices alias the same storage but carry independent cursors, so draining one leaves the
        // others whole. This is the primitive to reach for when several consumers must each read
        // the same bytes.
        assertEquals(0, first.remaining())
        assertEquals(8, second.remaining())
        assertEquals(8, base.remaining())
    }

    @Test
    fun slicesSeeTheSameBytes() {
        val handle = handleOf()
        val base = handle.asReadBuffer()
        val expected = ByteArray(8) { it.toByte() }

        val first = base.slice().readByteArray(8)
        val second = base.slice().readByteArray(8)

        assertEquals(expected.toList(), first.toList())
        assertEquals(expected.toList(), second.toList())
    }
}
