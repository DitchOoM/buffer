package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered

/**
 * Reads up to [byteCount] bytes from this [RawSource] into [dst], advancing
 * [dst]'s write position. Returns the number of bytes actually transferred.
 *
 * Copy semantics: bytes are copied out of the source into [dst]. Defaults to
 * filling [dst]'s remaining capacity. Writing past [dst]'s capacity fails fast
 * with the underlying overflow exception.
 *
 * ## Termination
 *
 * The read loop is driven by two independent stop conditions, and **the
 * byteCount check is evaluated first on every iteration**:
 *  - **Destination full** — once `total == byteCount` the loop exits without
 *    issuing another read, even if the source also happens to be exhausted at
 *    that exact boundary. The two conditions cannot race: if the last chunk
 *    both fills [dst] to [byteCount] and drains the source, this function
 *    returns [byteCount] directly — it never performs a further probe read
 *    that would observe EOF.
 *  - **Source exhausted** — if [RawSource.readAtMostTo] signals EOF (its `-1`
 *    convention) before `byteCount` bytes have been copied, the loop stops
 *    and this function returns the partial `total` copied so far.
 *
 * **EOF convention differs from [RawSource.readAtMostTo]:** that underlying
 * call returns `-1` at EOF, but this function folds EOF into its running
 * total and **never returns a negative value** — a source that is already
 * exhausted on entry returns `0`, not `-1`.
 */
public fun RawSource.readInto(
    dst: WriteBuffer,
    byteCount: Long = dst.remaining().toLong(),
): Long {
    require(byteCount >= 0L) { "byteCount ($byteCount) < 0" }
    if (byteCount == 0L) return 0L
    val sink = dst.asRawSink()
    val staging = Buffer()
    var total = 0L
    while (total < byteCount) {
        val read = readAtMostTo(staging, byteCount - total)
        if (read == -1L) break
        sink.write(staging, staging.size)
        total += read
    }
    return total
}

/**
 * Reads **all** remaining bytes from this [RawSource] into [dst] until the
 * source is exhausted, advancing [dst]'s write position. Returns the total
 * number of bytes transferred.
 *
 * Copy semantics. Unlike [readInto], there is no `byteCount` stop condition —
 * termination is driven **only** by the source reaching EOF (delegated to
 * kotlinx-io's `Source.transferTo`, which reads in a loop until its own
 * `readAtMostTo` returns `-1`). If the source produces more bytes than
 * [dst]'s remaining capacity, the write into [dst] fails fast with the
 * underlying overflow exception — there is no "destination full" outcome to
 * race against EOF, only success (source exhausted, [dst] had enough room) or
 * an overflow exception (source outlasted [dst]'s capacity).
 */
public fun RawSource.transferTo(dst: WriteBuffer): Long = buffered().transferTo(dst.asRawSink())

/**
 * Copies this buffer's remaining bytes (`position` until `limit`) into a new
 * kotlinx-io [Buffer]. **Does not change this buffer's position** — this is a
 * non-destructive snapshot.
 *
 * Copy semantics: the returned [Buffer] owns independent storage; mutating
 * this buffer afterwards does not affect it.
 */
public fun ReadBuffer.copyToKotlinxIoBuffer(): Buffer {
    val out = Buffer()
    val n = remaining()
    if (n == 0) return out
    val pos = position()
    val mma = managedMemoryAccess
    if (mma != null) {
        val start = mma.arrayOffset + pos
        out.write(mma.backingArray, start, start + n)
    } else {
        val tmp = copyToByteArray(n)
        position(pos)
        out.write(tmp)
    }
    return out
}

/**
 * Copies this kotlinx-io [Buffer]'s readable bytes into a new [PlatformBuffer]
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
        peek().transferTo(out.asRawSink())
    }
    out.resetForRead()
    return out
}
