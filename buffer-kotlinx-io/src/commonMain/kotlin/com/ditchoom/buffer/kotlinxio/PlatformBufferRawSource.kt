package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.ReadBuffer
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations

/**
 * Adapts a [ReadBuffer] to a kotlinx-io [RawSource].
 *
 * ## Semantics (LOCKED CONTRACT)
 *
 * This is a **streaming** bridge with **copy** semantics: each
 * [readAtMostTo] copies bytes out of [source] into the kotlinx-io [Buffer]
 * sink and advances [source]'s read position. A kotlinx-io `Buffer` always
 * owns its own segment storage, so there is no way to alias the underlying
 * [ReadBuffer] memory — the bytes cross the boundary exactly once and the two
 * sides are independent afterwards. Mutating [source] (or its backing) after
 * a read does not change bytes already handed to the sink.
 *
 * The copy lands **directly** in the sink's tail segment via
 * [UnsafeBufferOperations.writeToTail]: the segment transfer pulls a managed
 * backing array or native memory straight into the segment (on JVM/Android an
 * array-backed ByteBuffer additionally takes a `System.arraycopy` shortcut —
 * see `readIntoSegment`). This is a single copy for **both** backings — there
 * is no intermediate scratch array.
 *
 * ## Lifetime
 *
 * The returned source **must not outlive** [source]. If [source] is a pooled
 * buffer that is released (or a scoped buffer whose scope closes) while this
 * source is still held, the next read **fails fast** with the underlying
 * buffer's use-after-free `IllegalStateException` rather than reading
 * reclaimed memory. The access path is re-resolved on every read so a freed
 * wrapper is detected immediately — the source never caches a backing array.
 *
 * [close] marks this source closed; further reads throw [IllegalStateException].
 * Closing does **not** free [source] (this bridge does not own it).
 */
public fun ReadBuffer.asRawSource(): RawSource = PlatformBufferRawSource(this)

internal class PlatformBufferRawSource(
    private val source: ReadBuffer,
) : RawSource {
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "asRawSource() view has been closed" }
        require(byteCount >= 0L) { "byteCount ($byteCount) < 0" }
        val available = source.remaining()
        return if (byteCount == 0L) {
            0L
        } else if (available <= 0) {
            -1L
        } else {
            copyAtMostTo(sink, minOf(byteCount, available.toLong()).toInt())
        }
    }

    /**
     * Copies exactly [n] bytes from [source] into [sink], returning `n` as a [Long].
     * Split out of [readAtMostTo] so that function keeps a single, easy-to-follow exit
     * path for its three distinct outcomes (empty request / EOF / bytes copied).
     */
    @OptIn(UnsafeIoApi::class)
    private fun copyAtMostTo(
        sink: Buffer,
        n: Int,
    ): Long {
        var remaining = n
        while (remaining > 0) {
            val request = minOf(remaining, UnsafeBufferOperations.maxSafeWriteCapacity)
            val written =
                UnsafeBufferOperations.writeToTail(sink, request) { bytes, startIndex, endIndexExclusive ->
                    val toCopy = minOf(remaining, endIndexExclusive - startIndex)
                    // Single copy for BOTH backings: readIntoSegment pulls a managed backing array or
                    // native memory straight into the segment and advances position. For a freed pooled
                    // buffer this throws the use-after-free IllegalStateException (fail-fast).
                    source.readIntoSegment(bytes, startIndex, toCopy)
                    toCopy
                }
            remaining -= written
        }
        return n.toLong()
    }

    override fun close() {
        closed = true
    }
}
