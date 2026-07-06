package com.ditchoom.buffer.okio

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managedMemoryAccess
import okio.Buffer
import okio.Source
import okio.Timeout

/**
 * Okio's segment capacity is fixed at 8 KiB (see [Buffer.UnsafeCursor.expandBuffer]'s KDoc:
 * "must be positive and not greater than the capacity size of a single segment (8 KiB)").
 * `Segment.SIZE` itself is `internal`, so this bridge pins the request chunk to the documented
 * limit instead of depending on an unavailable constant.
 */
private const val MAX_SEGMENT_CAPACITY = 8192

/**
 * Adapts a [ReadBuffer] to an Okio [Source].
 *
 * ## Semantics (LOCKED CONTRACT)
 *
 * This is a **streaming** bridge with **copy** semantics: each [read] copies
 * bytes out of [source] into the Okio [Buffer] sink and advances [source]'s
 * read position. An Okio `Buffer` always owns its own segment storage, so
 * there is no way to alias the underlying [ReadBuffer] memory — the bytes
 * cross the boundary exactly once and the two sides are independent
 * afterwards. Mutating [source] (or its backing) after a read does not change
 * bytes already handed to the sink.
 *
 * When [source] exposes [com.ditchoom.buffer.ManagedMemoryAccess] the copy is
 * a single `Buffer.write(backingArray, …)`. Otherwise the copy lands
 * **directly** in the sink's tail segment via [Buffer.UnsafeCursor]
 * (`readAndWriteUnsafe` + `expandBuffer`): the segment transfer pulls native
 * memory straight into the segment array (on JVM/Android an array-backed
 * ByteBuffer additionally takes a `System.arraycopy` shortcut — see
 * `readIntoSegment`). Single copy for **both** backings — there is no
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
public fun ReadBuffer.asOkioSource(): Source = PlatformBufferOkioSource(this)

internal class PlatformBufferOkioSource(
    private val source: ReadBuffer,
) : Source {
    private var closed = false

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "asOkioSource() view has been closed" }
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
     * Split out of [read] so that function keeps a single, easy-to-follow exit path
     * for its three distinct outcomes (empty request / EOF / bytes copied).
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
            // Managed backing: one Buffer.write straight from the backing array.
            val pos = source.position()
            val start = mma.arrayOffset + pos
            sink.write(mma.backingArray, start, n)
            source.position(pos + n)
        } else {
            copySegmented(sink, n)
        }
        return n.toLong()
    }

    /**
     * Native-memory path: copies [n] bytes directly into the sink's tail segment array via
     * [Buffer.UnsafeCursor], one segment at a time — a single copy, with no intermediate
     * scratch array. [Buffer.UnsafeCursor.expandBuffer] may hand back more capacity than
     * requested (up to a full segment); any surplus is trimmed with `resizeBuffer` so the
     * sink never gains uninitialized bytes.
     */
    private fun copySegmented(
        sink: Buffer,
        n: Int,
    ) {
        var remaining = n
        val cursor = Buffer.UnsafeCursor()
        sink.readAndWriteUnsafe(cursor)
        try {
            while (remaining > 0) {
                val request = minOf(remaining, MAX_SEGMENT_CAPACITY)
                val before = sink.size
                cursor.expandBuffer(request)
                // readIntoSegment pulls native memory straight into the segment and advances
                // position; a freed pooled buffer throws its use-after-free
                // IllegalStateException (fail-fast).
                source.readIntoSegment(cursor.data!!, cursor.start, request)
                if (sink.size > before + request) {
                    cursor.resizeBuffer(before + request)
                }
                remaining -= request
            }
        } finally {
            cursor.close()
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}
