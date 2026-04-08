package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer

fun <T> Encoder<T>.encodeToBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): ReadBuffer {
    val growable = GrowableWriteBuffer(factory, initialSize = wireSizeHint)
    if (this is Codec<*>) {
        @Suppress("UNCHECKED_CAST")
        (this as Codec<T>).encode(growable, value, context)
    } else {
        encode(growable, value)
    }
    return growable.toReadBuffer()
}

fun <T> Codec<T>.encodeToBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): ReadBuffer {
    val growable = GrowableWriteBuffer(factory, initialSize = wireSizeHint)
    encode(growable, value, context)
    return growable.toReadBuffer()
}

/**
 * Testing utility: encodes [value], optionally verifies the wire bytes match [expectedBytes],
 * then decodes and returns the result. Intended for use in test suites to validate codec correctness.
 */
fun <T> Codec<T>.testRoundTrip(
    value: T,
    expectedBytes: ByteArray? = null,
    factory: BufferFactory = BufferFactory.Default,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
): T {
    val encoded = encodeToBuffer(value, factory, encodeContext)
    if (expectedBytes != null) {
        val actualBytes = encoded.readByteArray(encoded.remaining())
        check(actualBytes.contentEquals(expectedBytes)) {
            "Encoded bytes do not match expected: " +
                "expected=${expectedBytes.toHexString()}, " +
                "actual=${actualBytes.toHexString()}"
        }
        encoded.resetForRead()
    }
    return decode(encoded, decodeContext)
}

private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
