package com.ditchoom.buffer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression guards for a K/N-only process crash: calling
 * `NSData.subdataWithRange:` with a range beyond the data's length raises an
 * ObjC `NSRangeException` which Kotlin cannot catch — the process terminates
 * before `assertFailsWith` runs. The fix bounds-checks before touching ObjC
 * and throws a Kotlin [BufferUnderflowException] instead.
 *
 * Original reproducer lived downstream in
 * `mqtt/models-v5/MalformedPacketTests.remainingLengthExceedsAvailableDataThrows`.
 */
@OptIn(
    ExperimentalForeignApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
    kotlinx.cinterop.BetaInteropApi::class,
)
class ReadStringBoundsTest {
    @Test
    fun mutableDataBufferReadStringThrowsOnOverflow() {
        val data = NSMutableData.create(length = 4.convert())!!
        val buffer = MutableDataBuffer(data, ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        buffer.setLimit(4)
        assertFailsWith<BufferUnderflowException> { buffer.readString(1) }
    }

    @Test
    fun mutableDataBufferSliceReadStringThrowsOnOverflow() {
        // Matches the original MQTT stack: PUBLISH with remaining length = 32
        // but only 2 body bytes available; slice ends up with 4 bytes total and
        // the decoder asks for a 1-byte utf8 string at position 4.
        val data = NSMutableData.create(length = 4.convert())!!
        val parent = MutableDataBuffer(data, ByteOrder.BIG_ENDIAN)
        val slice = parent.slice()
        slice.position(slice.remaining())
        assertFailsWith<BufferUnderflowException> { slice.readString(1) }
    }

    @Test
    fun nsDataBufferReadStringThrowsOnOverflow() {
        val data: NSData = NSMutableData.create(length = 4.convert())!!
        val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        assertFailsWith<BufferUnderflowException> { buffer.readString(1) }
    }

    @Test
    fun nsDataBufferSliceReadStringThrowsOnOverflow() {
        val data: NSData = NSMutableData.create(length = 4.convert())!!
        val parent = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
        val slice = parent.slice()
        slice.position(slice.remaining())
        assertFailsWith<BufferUnderflowException> { slice.readString(1) }
    }

    @Test
    fun readStringThrowsOnNegativeLength() {
        val data = NSMutableData.create(length = 8.convert())!!
        val buffer = MutableDataBuffer(data, ByteOrder.BIG_ENDIAN)
        assertFailsWith<BufferUnderflowException> { buffer.readString(-1) }
    }

    /**
     * Exact reproducer of the original MQTT crash: caller sets limit past the
     * underlying NSData length (MQTT uses the wire remaining-length before the
     * body bytes have arrived), then reads a slice of that range. Internal
     * bounds check against [ReadBuffer.remaining] passes because the limit is
     * inflated; `subdataWithRange:` against the smaller parent NSData is what
     * fails.
     */
    @Test
    fun mutableDataBufferSliceReadStringThrowsWhenLimitExceedsUnderlyingData() {
        val data = NSMutableData.create(length = 4.convert())!!
        val parent = MutableDataBuffer(data, ByteOrder.BIG_ENDIAN)
        parent.setLimit(32) // simulate wire remaining-length > actual bytes
        val slice = parent.slice()
        slice.position(4) // past the 4-byte underlying NSData, still within slice limit
        assertFailsWith<BufferUnderflowException> { slice.readString(1) }
    }

    @Test
    fun nsDataBufferSliceReadStringThrowsWhenLimitExceedsUnderlyingData() {
        val data: NSData = NSMutableData.create(length = 4.convert())!!
        val parent = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
        parent.setLimit(32)
        val slice = parent.slice()
        slice.position(4)
        assertFailsWith<BufferUnderflowException> { slice.readString(1) }
    }
}
