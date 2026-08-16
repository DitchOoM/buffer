package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.SharedBytes
import kotlinx.coroutines.flow.StateFlow

/**
 * The element the writer hands to [OutboundWriter]'s `transmit` stage: either a message still to
 * be encoded (encode-on-writer keeps buffer lifetime and pool discipline inside the adopter's
 * transmit closure), or bytes already encoded once for fan-out. "Pre-encoded" is a queue-element
 * arm, not a connection flag — both send modes carry both arms.
 */
@ExperimentalFanoutApi
sealed interface Outgoing<out T> {
    /** Encode [message] on the writer, then write the frame fully. */
    @ExperimentalFanoutApi
    data class Encode<T>(
        val message: T,
    ) : Outgoing<T>

    /**
     * Write [view] fully — it is a private-cursor slice of shared, already-encoded bytes.
     * The writer owns the underlying reference; the transmit stage only writes.
     */
    @ExperimentalFanoutApi
    class Prewritten<T>(
        val view: ReadBuffer,
        /** The message the bytes encode, for loss reporting. */
        val origin: T,
    ) : Outgoing<T>
}

/** What the adopter's transmit stage reports back to the writer, per element. */
@ExperimentalFanoutApi
sealed interface TransmitOutcome {
    /** The frame reached the wire whole. */
    @ExperimentalFanoutApi
    data object Written : TransmitOutcome

    /**
     * The element failed to **encode** — deterministic, per-message, no bytes written. The
     * connection survives: in `AwaitWritten` the cause throws at the sender's call site; in
     * `Handoff` it reports through `onNotSent` as [NotSentReason.EncodeFailed].
     * Transport/write failures are different: transmit *throws* those, and they fail the
     * connection.
     */
    @ExperimentalFanoutApi
    data class EncodeFailed(
        val cause: Throwable,
    ) : TransmitOutcome
}

/** Thrown by [OutboundWriter.send]/[OutboundWriter.sendShared] once the writer is closed. */
@ExperimentalFanoutApi
class OutboundClosedException(
    /** Why the writer closed — the same cause carried by [ConnectionPhase.Closed]. */
    val closeCause: CloseCause,
) : IllegalStateException(
        "outbound writer is closed: $closeCause",
        (closeCause as? CloseCause.Failed)?.cause,
    )

/**
 * A connection-owned single writer: the component that makes send atomicity and serialization
 * structural rather than by-discipline.
 *
 * One writer coroutine — owned by this component, living on an internal scope whose lifetime
 * equals the component's ([close]/[abort] end it) — performs every transmit. Callers hand
 * elements off; they never run the write themselves, so cancelling a caller can never truncate a
 * frame, and concurrent senders cannot interleave. The [mode] decides how `send` completes; see
 * [SendMode].
 *
 * ## Contract with the adopter's [transmit]
 *
 * - Called serially, only from the writer coroutine, one [Outgoing] element at a time.
 * - Must write the frame **fully or throw** (`writeFully` semantics). A throw is a transport
 *   failure: the writer fails with [CloseCause.Failed], suspended senders and future sends get
 *   [OutboundClosedException], and remaining queued elements report not-sent.
 * - Encode failures are not throws — return [TransmitOutcome.EncodeFailed] instead; the
 *   connection survives them (see [TransmitOutcome]).
 *
 * ## Exactly-once accounting (the load-bearing invariant)
 *
 * Every accepted element leaves through exactly one of: transmit-completed, or the loss path
 * (mode-appropriate: `onNotSent` in Handoff, a thrown cause in AwaitWritten). Shared-bytes
 * references transferred via [sendShared] are released by this component exactly once, on
 * whichever path the element takes. Capacity eviction, linger expiry, `close()` racing
 * `abort()`, and writer failure are all instances of the same rule, not special cases.
 *
 * ## Close ladder
 *
 * [close] is graceful: the phase moves to [ConnectionPhase.Draining] (the phase transition and
 * the enqueue check are one atomic step — no send can slip into a drained queue), queued frames
 * flush (bounded by the Handoff `linger`, which escalates to abort on expiry; AwaitWritten
 * finishes the in-flight frame and fails the waiting senders), the writer joins, and the phase
 * reaches [ConnectionPhase.Closed]. [abort] is immediate: cancel the writer wherever it is —
 * truncating a frame on a dying connection harms nobody, which is the whole point of the
 * ownership design — and report the queue not-sent. Both are idempotent and converge under
 * concurrent calls.
 *
 * The prompt-cancellation edge, stated once: in `AwaitWritten`, a sender whose handoff succeeded
 * may still unwind with `CancellationException` while the writer finishes the frame — "cancelled"
 * does not imply "not sent". The writer completes every taken element's acknowledgement exactly
 * once regardless of whether anyone is still listening.
 */
@ExperimentalFanoutApi
class OutboundWriter<T>(
    private val mode: SendMode<T>,
    private val transmit: suspend (Outgoing<T>) -> TransmitOutcome,
) {
    /** The send/close lifecycle, reactively. Single source of truth — there is no separate flag. */
    val phase: StateFlow<ConnectionPhase>
        get() = TODO("implemented by the writer-component work")

    /**
     * Sends [message] per the [mode]'s completion semantics. Throws [OutboundClosedException]
     * once the writer is not [ConnectionPhase.Open].
     */
    suspend fun send(message: T): Unit = TODO("implemented by the writer-component work")

    /**
     * Sends already-encoded shared bytes. **Transfers one [SharedBytes] reference** — the caller
     * retains for this call, and this component releases exactly once on whichever path the
     * element takes. [origin] is the message the bytes encode, for loss reporting.
     */
    suspend fun sendShared(
        bytes: SharedBytes,
        origin: T,
    ): Unit = TODO("implemented by the writer-component work")

    /** Graceful close: drain per mode, join the writer, settle at [ConnectionPhase.Closed]. */
    suspend fun close(): Unit = TODO("implemented by the writer-component work")

    /** Immediate close: cancel the writer, report the queue not-sent, settle at [ConnectionPhase.Closed]. */
    suspend fun abort(): Unit = TODO("implemented by the writer-component work")
}
