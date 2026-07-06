package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managedMemoryAccess
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
 * When [source] exposes [com.ditchoom.buffer.ManagedMemoryAccess] the copy is
 * a single `Buffer.write(backingArray, …)` (benchmarked fastest for managed
 * backings at every size). Otherwise the copy lands **directly** in the sink's
 * tail segment via [UnsafeBufferOperations.writeToTail]: the segment transfer
 * pulls native memory straight into the segment array (on JVM/Android an
 * array-backed ByteBuffer additionally takes a `System.arraycopy` shortcut —
 * see `readIntoSegment`). Single copy for **both** backings — there is no
 * intermediate scratch array.
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
     *
     * Re-resolves the access path on every read: for a freed pooled buffer this throws
     * the underlying use-after-free IllegalStateException (fail-fast) instead of aliasing
     * a cached backing array that may have been reclaimed.
     */
    private fun copyAtMostTo(
        sink: Buffer,
        n: Int,
    ): Long {
        val mma = source.managedMemoryAccess
        if (mma != null) {
            // Managed backing: one Buffer.write straight from the backing array. Benchmarks
            // (JVM + macosArm64) show this beats the segment-level path at small sizes and
            // ties at large ones, so it stays the managed fast path.
            val pos = source.position()
            val start = mma.arrayOffset + pos
            sink.write(mma.backingArray, start, start + n)
            source.position(pos + n)
        } else {
            copySegmented(sink, n)
        }
        return n.toLong()
    }

    /**
     * Native-memory path: copies [n] bytes directly into the sink's tail segment array,
     * one segment at a time — a single copy, with no intermediate scratch array.
     *
     * The lambda captures only immutable state (`request` and `this`); capturing a mutable
     * loop variable would box it in a Ref wrapper and allocate per iteration on Kotlin/Native.
     */
    @OptIn(UnsafeIoApi::class)
    private fun copySegmented(
        sink: Buffer,
        n: Int,
    ) {
        var remaining = n
        while (remaining > 0) {
            val request = minOf(remaining, UnsafeBufferOperations.maxSafeWriteCapacity)
            // writeToTail guarantees at least `request` writable bytes, so the lambda copies
            // exactly `request`. readIntoSegment pulls native memory straight into the segment
            // and advances position; a freed pooled buffer throws its use-after-free
            // IllegalStateException (fail-fast).
            UnsafeBufferOperations.writeToTail(sink, request) { bytes, startIndex, _ ->
                source.readIntoSegment(bytes, startIndex, request)
                request
            }
            remaining -= request
        }
    }

    override fun close() {
        closed = true
    }
}
