package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

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
            wireSize(value, context)
        } catch (_: NotImplementedError) {
            -1
        }
    return if (knownSize >= 0) {
        val buf = factory.allocate(knownSize)
        encode(buf, value, context)
        buf.resetForRead()
        buf
    } else {
        val growable = GrowableWriteBuffer(factory)
        encode(growable, value, context)
        growable.toReadBuffer()
    }
}

fun <T> Codec<T>.encodeToBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): ReadBuffer = (this as Encoder<T>).encodeToBuffer(value, factory, context)

/**
 * Encodes a value of unknown wire size by handing the caller a growable [WriteBuffer]
 * and returning the resulting [ReadBuffer]. The buffer doubles on overflow, so callers
 * that don't know the size in advance — e.g. wrappers around user-supplied
 * `WriteBuffer.(P) -> Unit` lambdas in mqtt's `eagerEncode` helpers — get the same
 * behavior without ceremony around an `Encoder<P>` adapter.
 *
 * For codec-driven encoding where the size IS known, prefer [Encoder.encodeToBuffer]
 * which allocates the exact size up front via [Encoder.wireSize].
 */
fun encodeWithGrowth(
    factory: BufferFactory = BufferFactory.Default,
    initialSize: Int = 256,
    write: (WriteBuffer) -> Unit,
): ReadBuffer {
    val growable = GrowableWriteBuffer(factory, initialSize = initialSize)
    write(growable)
    return growable.toReadBuffer()
}

/**
 * Testing utility: encodes [value], optionally verifies the wire bytes match [expectedBytes],
 * then decodes and returns the result. Intended for use in test suites to validate codec correctness.
 *
 * Doubles as a Guard 1 (payload-only round-trip) check: asserts that
 *   - `wireSize` equals the byte count produced by `encode`, and
 *   - `decode` consumes exactly those payload bytes (no over- or under-read).
 *
 * Together these assertions catch regressions where a codec re-introduces internal
 * length-prefix framing in violation of the [Codec] payload-only contract.
 */
fun <T> Codec<T>.testRoundTrip(
    value: T,
    expectedBytes: ByteArray? = null,
    factory: BufferFactory = BufferFactory.Default,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
): T {
    val encoded = encodeToBuffer(value, factory, encodeContext)
    val expectedWireSize = wireSize(value, encodeContext)
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
    val decoded = decode(encoded, decodeContext)
    check(encoded.remaining() == 0) {
        "decode must consume exactly the payload bytes (Guard 1): " +
            "${encoded.remaining()} byte(s) left over after decode of ${this::class.simpleName}"
    }
    return decoded
}

private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
