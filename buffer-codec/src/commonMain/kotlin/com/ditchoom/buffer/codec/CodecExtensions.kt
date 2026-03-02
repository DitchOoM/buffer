package com.ditchoom.buffer.codec

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate

fun <T> Codec<T>.encodeToBuffer(
    value: T,
    zone: AllocationZone = AllocationZone.Direct,
): ReadBuffer {
    val size = sizeOf(value)
    val bufferSize = size ?: 1024
    val buffer = PlatformBuffer.allocate(bufferSize, zone)
    encode(buffer, value)
    buffer.resetForRead()
    return buffer
}

fun <T> Codec<T>.testRoundTrip(
    value: T,
    expectedBytes: ByteArray? = null,
): T {
    val encoded = encodeToBuffer(value)
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
