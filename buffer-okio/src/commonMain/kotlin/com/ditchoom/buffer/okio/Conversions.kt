package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import okio.Buffer
import okio.ByteString
import okio.Source
import okio.buffer

/**
 * Reads up to [byteCount] bytes from this [Source] into [dst], advancing
 * [dst]'s write position. Returns the number of bytes actually transferred
 * (may be less than [byteCount] if the source is exhausted first).
 *
 * Copy semantics: bytes are copied out of the source into [dst]. Defaults to
 * filling [dst]'s remaining capacity. Writing past [dst]'s capacity fails fast
 * with the underlying overflow exception.
 */
public fun Source.readInto(
    dst: WriteBuffer,
    byteCount: Long = dst.remaining().toLong(),
): Long {
    require(byteCount >= 0L) { "byteCount ($byteCount) < 0" }
    if (byteCount == 0L) return 0L
    val sink = dst.asOkioSink()
    val staging = Buffer()
    var total = 0L
    while (total < byteCount) {
        val readCount = read(staging, byteCount - total)
        if (readCount == -1L) break
        sink.write(staging, staging.size)
        total += readCount
    }
    return total
}

/**
 * Reads **all** remaining bytes from this [Source] into [dst] until the
 * source is exhausted, advancing [dst]'s write position. Returns the total
 * number of bytes transferred.
 *
 * Copy semantics. Fails fast with the underlying overflow exception if the
 * source produces more bytes than [dst]'s remaining capacity.
 */
public fun Source.transferTo(dst: WriteBuffer): Long = buffer().readAll(dst.asOkioSink())

/**
 * Copies this buffer's remaining bytes (`position` until `limit`) into a new
 * Okio [Buffer]. **Does not change this buffer's position** — this is a
 * non-destructive snapshot.
 *
 * Copy semantics: the returned [Buffer] owns independent storage; mutating
 * this buffer afterwards does not affect it.
 */
public fun ReadBuffer.copyToOkioBuffer(): Buffer {
    val out = Buffer()
    val n = remaining()
    if (n == 0) return out
    val pos = position()
    val mma = managedMemoryAccess
    if (mma != null) {
        val start = mma.arrayOffset + pos
        out.write(mma.backingArray, start, n)
    } else {
        val tmp = copyToByteArray(n)
        position(pos)
        out.write(tmp)
    }
    return out
}

/**
 * Copies this buffer's remaining bytes into a new immutable Okio [ByteString].
 *
 * Non-destructive snapshot (does not change this buffer's position). Copy
 * semantics: a [ByteString] is immutable, so the bytes are always copied —
 * there is no unsafe-wrap variant.
 */
public fun ReadBuffer.copyToByteString(): ByteString = copyToOkioBuffer().snapshot()

/**
 * Copies this Okio [Buffer]'s readable bytes into a new [PlatformBuffer]
 * allocated by [factory], leaving the result positioned for reading
 * (`position = 0`, `limit = size`).
 *
 * **Does not consume this [Buffer]** — the copy is taken through a peek, so the
 * original remains readable. Copy semantics: mutating this [Buffer] afterwards
 * does not affect the returned [PlatformBuffer].
 *
 * @param factory allocator for the destination buffer (default [BufferFactory.Default]).
 * @param byteOrder byte order for the destination buffer's multi-byte reads.
 */
public fun Buffer.copyToPlatformBuffer(
    factory: BufferFactory = BufferFactory.Default,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer {
    val n = size
    require(n <= Int.MAX_VALUE.toLong()) { "Buffer size ($n) exceeds Int.MAX_VALUE" }
    val out = factory.allocate(n.toInt(), byteOrder)
    if (n > 0L) {
        peek().readAll(out.asOkioSink())
    }
    out.resetForRead()
    return out
}

/**
 * Copies this immutable Okio [ByteString] into a new [PlatformBuffer] allocated
 * by [factory], leaving the result positioned for reading (`position = 0`,
 * `limit = size`).
 *
 * Copy semantics: [ByteString] is immutable and does not expose its backing
 * array, so this always copies — there is no unsafe-wrap variant.
 *
 * @param factory allocator for the destination buffer (default [BufferFactory.Default]).
 * @param byteOrder byte order for the destination buffer's multi-byte reads.
 */
public fun ByteString.copyToPlatformBuffer(
    factory: BufferFactory = BufferFactory.Default,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer {
    val staging = Buffer()
    if (size > 0) staging.write(this)
    return staging.copyToPlatformBuffer(factory, byteOrder)
}
