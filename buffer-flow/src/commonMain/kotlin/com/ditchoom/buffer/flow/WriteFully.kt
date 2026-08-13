package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Duration

/**
 * Write **every** byte of [buffer] to this sink, resuming until it is drained.
 *
 * [ByteSink.write] returns [BytesWritten] because a write may be PARTIAL — the contract calls the
 * post-write position "the resume point for a partial write's residue". A caller that issues one
 * `write` and discards the count therefore has a latent truncation bug: it looks correct against every
 * sink that happens to accept everything, and silently loses the tail against one that does not.
 *
 * Plenty of sinks never expose it — one that copies into memory, or that loops internally until the
 * buffer is gone, always reports the whole thing. A QUIC stream does expose it: it can buffer only as
 * many bytes as its flow-control credit currently allows and reports that count (a fully blocked
 * stream blocks; a PARTIALLY open one returns early by design, which is the normal state at a window
 * boundary).
 *
 * **For a length-prefixed protocol a short write is corruption, not loss.** The length header the peer
 * has already read still declares the full size, so the peer keeps reading and consumes whatever
 * FOLLOWS as this message's tail: the truncated message arrives at exactly the right length with a
 * neighbour's bytes inside it, the swallowed messages never arrive, and the stream never re-aligns.
 * Any framed writer over a byte stream wants this, which is why it lives here beside [ByteSink] rather
 * than being re-implemented per protocol.
 *
 * Resumption is driven by the reported COUNT rather than by the cursor, so both sink shapes work: the
 * position is re-derived after every call, which neither double-advances a contract-compliant sink nor
 * strands one that leaves the cursor alone.
 *
 * Returns [Unit] deliberately — there is no residue left to reason about and no count to accidentally
 * ignore. Call [ByteSink.write] directly only when implementing a sink that must report a partial
 * count faithfully to ITS caller.
 *
 * @throws ByteSinkStalledException if the sink reports no progress while bytes are still pending.
 */
public suspend fun ByteSink.writeFully(
    buffer: ReadBuffer,
    deadline: Duration,
) {
    while (buffer.remaining() > 0) {
        val before = buffer.position()
        val written = write(buffer, deadline).count
        if (written <= 0) {
            throw ByteSinkStalledException(accepted = written, pending = buffer.remaining())
        }
        val resumeAt = before + written
        if (buffer.position() != resumeAt) buffer.position(resumeAt)
    }
}

/**
 * [writeFully] using the sink's injected [ByteSink.writePolicy] — the adapter rule ("propagate, don't
 * clobber"): the leaf owns the deadline policy for its direction. The policy bounds each underlying
 * call, as it always has, not the loop as a whole.
 */
public suspend fun ByteSink.writeFully(buffer: ReadBuffer): Unit = writeFully(buffer, writePolicy.toDeadline())

/**
 * A [ByteSink] reported **no progress** while bytes were still pending, so a write that must complete
 * in full cannot make headway.
 *
 * This is a sink CONTRACT violation, not back-pressure: back-pressure is expected to block inside
 * `write` and then report at least one byte. A sink that instead returns zero forever leaves a caller
 * looping with nothing to do — and returning early instead would silently truncate the message, which
 * for a framed protocol is corruption rather than loss.
 *
 * Carries the counts as typed fields so a caller can discriminate and report without parsing text.
 */
public class ByteSinkStalledException(
    /** Bytes the sink accepted on the call that made no progress (zero, or a defensive negative). */
    public val accepted: Int,
    /** Bytes still waiting to be written when the sink stalled. */
    public val pending: Int,
) : IllegalStateException(
        "byte sink accepted $accepted bytes with $pending still pending; cannot complete the write " +
            "without truncating the message",
    )
