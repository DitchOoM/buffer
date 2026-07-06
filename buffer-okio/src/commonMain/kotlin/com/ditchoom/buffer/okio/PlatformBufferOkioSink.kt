package com.ditchoom.buffer.okio

import com.ditchoom.buffer.WriteBuffer
import okio.Buffer
import okio.Sink
import okio.Timeout

/**
 * Adapts a [WriteBuffer] to an Okio [Sink].
 *
 * ## Semantics (LOCKED CONTRACT)
 *
 * This is a **streaming** bridge with **copy** semantics: each [write] drains
 * bytes out of the Okio source [Buffer] into [sink] and advances [sink]'s
 * write position. Bytes cross the boundary exactly once.
 *
 * The copy reads **directly** from the source's head segment via
 * [Buffer.UnsafeCursor] (`readUnsafe` + `seek(0)`): the segment transfer
 * pushes the segment slice into a managed backing array or native memory (on
 * JVM/Android an array-backed ByteBuffer additionally takes a
 * `System.arraycopy` shortcut — see `writeSegmentBytes`). This is a single
 * copy for **both** backings — there is no intermediate scratch array. The
 * consumed head bytes are then discarded from `source` with `skip`, which is
 * an O(1) segment-pointer adjustment, not a second copy.
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
public fun WriteBuffer.asOkioSink(): Sink = PlatformBufferOkioSink(this)

internal class PlatformBufferOkioSink(
    private val sink: WriteBuffer,
) : Sink {
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "asOkioSink() view has been closed" }
        require(byteCount >= 0L) { "byteCount ($byteCount) < 0" }
        require(byteCount <= source.size) {
            "byteCount ($byteCount) > source.size (${source.size})"
        }

        var remaining = byteCount
        while (remaining > 0L) {
            val transferred = transferChunk(source, remaining)
            if (transferred <= 0) break
            source.skip(transferred.toLong())
            remaining -= transferred.toLong()
        }
    }

    /**
     * Drains at most [remaining] bytes from [source]'s head segment into [sink], returning the
     * number of bytes actually transferred (`<= 0` signals the source is exhausted). The head
     * segment is read via a read-only [Buffer.UnsafeCursor] seeked to offset zero; the caller is
     * responsible for removing the transferred bytes from [source] with `skip`.
     */
    private fun transferChunk(
        source: Buffer,
        remaining: Long,
    ): Int {
        val cursor = Buffer.UnsafeCursor()
        source.readUnsafe(cursor)
        try {
            val avail = cursor.seek(0L)
            if (avail <= 0) return 0
            val n = minOf(remaining, avail.toLong()).toInt()
            // Single copy for BOTH backings: writeSegmentBytes pushes the segment slice into the
            // managed backing or native memory and advances position. A freed pooled buffer throws
            // its use-after-free IllegalStateException; an over-capacity write throws overflow
            // (fail-fast).
            sink.writeSegmentBytes(cursor.data!!, cursor.start, n)
            return n
        } finally {
            cursor.close()
        }
    }

    override fun flush() {
        check(!closed) { "asOkioSink() view has been closed" }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}
