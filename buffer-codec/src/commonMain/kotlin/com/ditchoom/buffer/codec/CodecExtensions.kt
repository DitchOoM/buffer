package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer

/**
 * Encodes [value] into a fresh [ReadBuffer] sized via [Encoder.wireSize]. Generated
 * codecs override `wireSize` with an exact byte-count formula so allocation is
 * one-shot, no grow-and-copy.
 *
 * For hand-written encoders that don't override `wireSize` (the throwing default),
 * falls back to a growable buffer that doubles on overflow. Mqtt's lambda-wrapping
 * `Encoder<P>` adapters in `eagerEncode` rely on this fallback.
 */
fun <T> Encoder<T>.encodeToBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): ReadBuffer {
    val knownSize =
        try {
            wireSize(value)
        } catch (_: NotImplementedError) {
            -1
        }
    return if (knownSize >= 0) {
        val buf = factory.allocate(knownSize)
        if (this is Codec<*>) {
            @Suppress("UNCHECKED_CAST")
            (this as Codec<T>).encode(buf, value, context)
        } else {
            encode(buf, value)
        }
        buf.resetForRead()
        buf
    } else {
        val growable = GrowableWriteBuffer(factory)
        if (this is Codec<*>) {
            @Suppress("UNCHECKED_CAST")
            (this as Codec<T>).encode(growable, value, context)
        } else {
            encode(growable, value)
        }
        growable.toReadBuffer()
    }
}

fun <T> Codec<T>.encodeToBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): ReadBuffer = (this as Encoder<T>).encodeToBuffer(value, factory, context)

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
    val expectedWireSize = wireSize(value)
    check(expectedWireSize == encoded.remaining()) {
        "wireSize must match encoded byte count: wireSize=$expectedWireSize, encoded=${encoded.remaining()}"
    }
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
