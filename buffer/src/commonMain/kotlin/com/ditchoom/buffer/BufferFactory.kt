package com.ditchoom.buffer

import kotlin.math.roundToInt

@Deprecated(
    "Use utf8Size() for exact UTF-8 sizing, or toReadBuffer(Utf8.Lenient, SizeHint.UpperBound) " +
        "for allocation. Removed in v7.",
)
fun CharSequence.maxBufferSize(charset: Charset): Int = (charset.maxBytesPerChar * this.length).roundToInt()

/**
 * Encodes this string into a fresh [ReadBuffer].
 *
 * Defaults to [BufferFactory.managed] because the backing buffer is dropped without an
 * explicit free (only its slice is returned): on platforms whose default factory hands
 * out owning native buffers (Linux `NativeBuffer`, large Android allocations), a
 * native-memory default would leak the staging allocation. Pass a [factory] explicitly
 * to control the allocation — with an owning factory the caller is responsible for the
 * backing memory's lifetime.
 */
fun String.toReadBuffer(
    charset: Charset = Charset.UTF8,
    factory: BufferFactory = BufferFactory.managed(),
): ReadBuffer {
    if (this == "") {
        return ReadBuffer.EMPTY_BUFFER
    }

    @Suppress("DEPRECATION")
    val maxBytes = maxBufferSize(charset)
    val buffer = factory.allocate(maxBytes)
    buffer.writeString(this, charset)
    buffer.resetForRead()
    return buffer.slice()
}

@Deprecated(
    "Use utf8Size(), whose count is guaranteed to match writeText(text, Utf8.Lenient) " +
        "byte-for-byte on every platform. Removed in v7.",
    ReplaceWith("this.utf8Size()", "com.ditchoom.buffer.utf8Size"),
)
fun CharSequence.utf8Length(): Int = Utf8TextEncoder.sizeSubstituting(this)
