package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Adapts a [WriteBuffer] to a kotlinx-io [RawSink].
 *
 * ## Semantics (LOCKED CONTRACT)
 *
 * This is a **streaming** bridge with **copy** semantics: each [write] drains
 * bytes out of the kotlinx-io source [Buffer] into [sink] and advances
 * [sink]'s write position. Bytes cross the boundary exactly once. When [sink]
 * exposes [com.ditchoom.buffer.ManagedMemoryAccess] the copy reads directly
 * into the backing array; otherwise bytes are staged through a bounded scratch
 * array. Both paths preserve the copy contract.
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

private const val SCRATCH_CHUNK = 8192

internal class PlatformBufferRawSink(
    private val sink: WriteBuffer,
) : RawSink {
    private var closed = false
    private var scratch: ByteArray? = null

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
            val pos = sink.position()
            // Re-resolve the access path each iteration so a freed pooled buffer throws its
            // use-after-free IllegalStateException instead of writing into reclaimed memory.
            val mma = sink.managedMemoryAccess
            if (mma != null) {
                val cap = sink.limit() - pos
                val n = minOf(remaining, cap.toLong()).toInt()
                val start = mma.arrayOffset + pos
                val read = source.readAtMostTo(mma.backingArray, start, start + n)
                if (read <= 0) break
                sink.position(pos + read)
                remaining -= read.toLong()
            } else {
                val buf = scratch ?: ByteArray(SCRATCH_CHUNK).also { scratch = it }
                val n = minOf(remaining, buf.size.toLong()).toInt()
                val read = source.readAtMostTo(buf, 0, n)
                if (read <= 0) break
                sink.writeBytes(buf, 0, read)
                remaining -= read.toLong()
            }
        }
    }

    override fun flush() {
        check(!closed) { "asRawSink() view has been closed" }
    }

    override fun close() {
        closed = true
        scratch = null
    }
}
