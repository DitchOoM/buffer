package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ExperimentalFanoutApi
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * How a connection's `send` completes, once the connection owns its writer.
 *
 * Two things are **invariants** of a conforming connection and hold in every mode: a message
 * reaches the wire whole or not at all (cancelling the sender can never truncate a frame), and
 * concurrent `send`s never interleave. What a mode chooses is *policy*: who waits, and what
 * happens when nobody can.
 *
 * `SendMode` is contravariant — a mode only ever **consumes** messages (via
 * [Handoff.onNotSent]), so a mode that handles `Any?` serves every connection. That is why
 * [AwaitWritten] is a single shared object usable as the default for any `T`, with no casts
 * (the `Comparator<in T>` pattern).
 */
sealed interface SendMode<in T> {
    /**
     * `send` returns when the frame is on the wire; write errors throw at the call site.
     *
     * The drop-in default: observable semantics match a direct write — send-then-close needs no
     * drain contract, and failures surface synchronously to the sender. What changes is only what
     * was broken: the connection-owned writer finishes the frame even if the sender is cancelled
     * mid-wait (the sender abandons the *wait*, never the *write*), and concurrent senders are
     * serialized. One documented edge follows: a cancelled `send` may have sent anyway — the frame
     * completes whole and cannot be un-sent. This mode has **zero configuration** because its
     * queue is its suspended senders: capacity, overflow, and linger are not defaulted here, they
     * are inexpressible.
     */
    data object AwaitWritten : SendMode<Any?>

    /**
     * `send` returns on enqueue; no sender ever waits on a peer; loss is possible and always
     * reported through [onNotSent].
     *
     * The fan-out mode: a producer looping over many connections hands each its message in
     * O(enqueue) and cannot be stalled by any single slow peer. All four fields are required —
     * the library guarantees the invariants, the adopter states the policy. A message that does
     * not reach the wire is reported exactly once with a [NotSentReason]; no silent-loss path
     * exists.
     *
     * [linger] lives here and not on the connection because only this mode has an unattended
     * queue to drain at close: in [AwaitWritten], graceful close finishes the in-flight frame and
     * fails the remaining senders, who are by construction present to hear it.
     */
    @ExperimentalFanoutApi
    class Handoff<in T>(
        /** Queue bound, in messages. The overflow policy engages at exactly this depth. */
        val capacity: OutboundCapacity,
        /** What `send` does when the queue is at [capacity]. */
        val onCapacity: CapacityBehavior,
        /** How long a graceful close may drain the queue before escalating to abort. */
        val linger: Linger,
        /**
         * Invoked exactly once for every accepted message that will not reach the wire —
         * capacity eviction, close-time discard, or a message that failed to encode. Runs
         * outside the queue lock: re-entrant `send`/`close` from the handler is legal.
         *
         * **Must not throw.** A handler that throws on the writer's reporting path fails the
         * writer with the thrown error (`CloseCause.Failed`) — surfaced loudly rather than left
         * as a dead writer under an Open phase — and any reports still owed become best-effort.
         * Count, log, hand off; do not raise.
         */
        val onNotSent: suspend (T, NotSentReason) -> Unit,
    ) : SendMode<T>
}

/**
 * A [SendMode.Handoff] queue bound, counted in **messages** (the queue holds messages, never
 * encoded buffers). Value-typed so the unit is in the signature and illegal depths are
 * unconstructible.
 */
@ExperimentalFanoutApi
@JvmInline
value class OutboundCapacity(
    val messages: Int,
) {
    init {
        require(messages > 0) { "outbound capacity must be positive: $messages" }
    }
}

/** What [SendMode.Handoff]'s `send` does when the queue is full. */
@ExperimentalFanoutApi
sealed interface CapacityBehavior {
    /** Backpressure: `send` suspends until space frees. A cancelled wait loses only its own message. */
    @ExperimentalFanoutApi
    data object Suspend : CapacityBehavior

    /** Evict the queue head (stale-first) to admit the new message; the evictee goes to `onNotSent`. */
    @ExperimentalFanoutApi
    data object DropOldest : CapacityBehavior

    /** Reject the incoming message; it goes to `onNotSent`, the queue is untouched. */
    @ExperimentalFanoutApi
    data object DropNewest : CapacityBehavior
}

/**
 * How long a graceful close may keep draining a [SendMode.Handoff] queue before escalating to
 * abort — the same shape as `WritePolicy.UntilClosed`/`Bounded`, for the same reason: a stalled
 * peer must not turn `close()` into a hang. A zero linger is spelled `abort()`, not `Bounded(0)`.
 */
@ExperimentalFanoutApi
sealed interface Linger {
    /** Drain until the queue is empty, however long the writes take (each still bounded by the sink's `WritePolicy`). */
    @ExperimentalFanoutApi
    data object UntilDrained : Linger

    /** Drain for at most [timeout], then escalate to abort; the remainder is reported not-sent. */
    @ExperimentalFanoutApi
    data class Bounded(
        val timeout: Duration,
    ) : Linger {
        init {
            require(timeout.isPositive()) { "linger timeout must be positive: $timeout (zero linger is spelled abort())" }
        }
    }
}

/**
 * Why an accepted message will not reach the wire. Reported through
 * [SendMode.Handoff.onNotSent], exactly once per lost message, with the message itself — the
 * handback is always the *message*, never a buffer someone must free.
 */
@ExperimentalFanoutApi
sealed interface NotSentReason {
    /** Evicted (or rejected) by [CapacityBehavior] with the queue at capacity. */
    @ExperimentalFanoutApi
    data object CapacityExceeded : NotSentReason

    /** The connection closed — gracefully, by abort, or by failure — with this message still queued. */
    @ExperimentalFanoutApi
    data class ConnectionClosed(
        val cause: CloseCause,
    ) : NotSentReason

    /**
     * The message failed to encode on the writer. The connection survives: encode failure is
     * per-message and deterministic, and no bytes of the frame reached the wire.
     */
    @ExperimentalFanoutApi
    data class EncodeFailed(
        val cause: Throwable,
    ) : NotSentReason
}
