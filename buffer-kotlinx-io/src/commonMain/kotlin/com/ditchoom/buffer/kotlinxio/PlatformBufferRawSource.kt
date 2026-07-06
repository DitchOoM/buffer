package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managedMemoryAccess
import kotlinx.io.Buffer
import kotlinx.io.RawSource

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
 * a single `Buffer.write(backingArray, …)`; otherwise the bytes are staged
 * through a bounded scratch array. Both paths preserve the copy contract.
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

private const val SCRATCH_CHUNK = 8192

internal class PlatformBufferRawSource(
    private val source: ReadBuffer,
) : RawSource {
    private var closed = false
    private var scratch: ByteArray? = null

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
    private fun copyAtMostTo(
        sink: Buffer,
        n: Int,
    ): Long {
        val pos = source.position()

        // Re-resolve the access path on every read: for a freed pooled buffer this throws the
        // underlying use-after-free IllegalStateException (fail-fast) instead of aliasing a cached
        // backing array that may have been reclaimed.
        val mma = source.managedMemoryAccess
        if (mma != null) {
            val start = mma.arrayOffset + pos
            sink.write(mma.backingArray, start, start + n)
            source.position(pos + n)
        } else {
            val buf = scratch ?: ByteArray(minOf(n, SCRATCH_CHUNK)).also { scratch = it }
            var remaining = n
            while (remaining > 0) {
                val chunk = minOf(remaining, buf.size)
                source.readInto(buf, 0, chunk)
                sink.write(buf, 0, chunk)
                remaining -= chunk
            }
        }
        return n.toLong()
    }

    override fun close() {
        closed = true
        scratch = null
    }
}
