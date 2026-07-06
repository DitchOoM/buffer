package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.WriteBuffer
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations

/**
 * Adapts a [WriteBuffer] to a kotlinx-io [RawSink].
 *
 * ## Semantics (LOCKED CONTRACT)
 *
 * This is a **streaming** bridge with **copy** semantics: each [write] drains
 * bytes out of the kotlinx-io source [Buffer] into [sink] and advances
 * [sink]'s write position. Bytes cross the boundary exactly once.
 *
 * The copy reads **directly** from the source's head segment via
 * [UnsafeBufferOperations.readFromHead]: the segment transfer pushes the
 * segment slice into a managed backing array or native memory (on JVM/Android
 * an array-backed ByteBuffer additionally takes a `System.arraycopy` shortcut
 * — see `writeSegmentBytes`). This is a single copy for **both** backings —
 * there is no intermediate scratch array.
 *
 * Writing more than [sink]'s remaining capacity fails fast with the underlying
 * buffer's overflow exception — this sink never grows [sink].
 *
 * ## Lifetime
 *
 * The returned sink **must not outlive** [sink]. If [sink] is a pooled buffer
 * that is released while this sink is still held, the next write **fails fast**
 * with the underlying use-after-free `IllegalStateException`. [flush] is a
 * no-op (writes land in [sink] immediately). [close] marks this sink closed;
 * further writes throw [IllegalStateException]. Closing does **not** free
 * [sink] (this bridge does not own it).
 */
public fun WriteBuffer.asRawSink(): RawSink = PlatformBufferRawSink(this)

internal class PlatformBufferRawSink(
    private val sink: WriteBuffer,
) : RawSink {
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "asRawSink() view has been closed" }
        require(byteCount >= 0L) { "byteCount ($byteCount) < 0" }
        require(byteCount <= source.size) {
            "byteCount ($byteCount) > source.size (${source.size})"
        }

        var remaining = byteCount
        while (remaining > 0L) {
            val read = transferChunk(source, remaining)
            if (read <= 0) break
            remaining -= read.toLong()
        }
    }

    /**
     * Drains at most [remaining] bytes from [source]'s head segment into [sink], returning the number
     * of bytes actually transferred (`<= 0` signals the source is exhausted). Split out of [write] so
     * the loop body has a single jump statement instead of one `break` per branch.
     */
    @OptIn(UnsafeIoApi::class)
    private fun transferChunk(
        source: Buffer,
        remaining: Long,
    ): Int =
        UnsafeBufferOperations.readFromHead(source) { bytes, startIndex, endIndexExclusive ->
            val avail = endIndexExclusive - startIndex
            val n = minOf(remaining, avail.toLong()).toInt()
            // Single copy for BOTH backings: writeSegmentBytes pushes the segment slice into the
            // managed backing or native memory and advances position. A freed pooled buffer throws its
            // use-after-free IllegalStateException; an over-capacity write throws overflow (fail-fast).
            sink.writeSegmentBytes(bytes, startIndex, n)
            n
        }

    override fun flush() {
        check(!closed) { "asRawSink() view has been closed" }
    }

    override fun close() {
        closed = true
    }
}
