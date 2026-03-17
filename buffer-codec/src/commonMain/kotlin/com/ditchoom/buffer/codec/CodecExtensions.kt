package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer

fun <T> Codec<T>.encodeToBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val size = sizeOf(value)
    val bufferSize = size ?: 1024
    val buffer = factory.allocate(bufferSize)
    encode(buffer, value)
    val bytesWritten = buffer.position()
    buffer.setLimit(bytesWritten)
    buffer.position(0)
    return buffer
}

/**
 * Testing utility: encodes [value], optionally verifies the wire bytes match [expectedBytes],
 * then decodes and returns the result. Intended for use in test suites to validate codec correctness.
 */
fun <T> Codec<T>.testRoundTrip(
    value: T,
    expectedBytes: ByteArray? = null,
    factory: BufferFactory = BufferFactory.Default,
): T {
    val encoded = encodeToBuffer(value, factory)
    if (expectedBytes != null) {
        val actualBytes = encoded.readByteArray(encoded.remaining())
        check(actualBytes.contentEquals(expectedBytes)) {
            "Encoded bytes do not match expected: " +
                "expected=${expectedBytes.toHexString()}, " +
                "actual=${actualBytes.toHexString()}"
        }
        encoded.resetForRead()
    }
    return decode(encoded)
}

private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
