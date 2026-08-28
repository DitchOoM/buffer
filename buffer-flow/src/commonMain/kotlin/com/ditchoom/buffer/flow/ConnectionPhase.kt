package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ExperimentalFanoutApi

/**
 * The send/close lifecycle of a connection that owns its writer, exposed reactively as a
 * `StateFlow<ConnectionPhase>`.
 *
 * A sealed phase instead of boolean flags: `Closed` cannot exist without a [CloseCause], so
 * "the connection is dead but nobody knows why" is unrepresentable, and the drain window is an
 * explicit state instead of an inferred flag combination.
 *
 * This deliberately coexists with the *transport* layer's establishment lifecycle
 * (Initialized/Connecting/Connected/Disconnected in socket implementations): that vocabulary
 * describes reaching the peer; this one describes the writer-owning connection's send/close
 * ladder. The coexistence is intent, not drift.
 *
 * Experimental alongside every producer and consumer of it ([OutboundWriter.phase],
 * [OutboundClosedException], [NotSentReason.ConnectionClosed]). Freezing an exhaustively-`when`-able
 * hierarchy on day one is what makes a later discovery — a needed arm, a renamed cause — a major
 * version instead of an experimental-round correction. It costs zero stable consumers today.
 */
@ExperimentalFanoutApi
sealed interface ConnectionPhase {
    /** Accepting sends; the writer is live. */
    data object Open : ConnectionPhase

    /** Graceful close in progress: no new sends; queued frames are flushing. */
    data object Draining : ConnectionPhase

    /** Terminal. The cause is always present. */
    data class Closed(
        val cause: CloseCause,
    ) : ConnectionPhase
}

/** Why a connection reached [ConnectionPhase.Closed]. Experimental for the same reason. */
@ExperimentalFanoutApi
sealed interface CloseCause {
    /** Graceful close completed: every accepted frame reached the wire. */
    data object Graceful : CloseCause

    /** Abort — explicit `abort()`, or a graceful close whose linger expired. */
    data object Aborted : CloseCause

    /**
     * The writer or transport failed. At this layer the cause is the transport [Throwable];
     * richer sealed classification belongs to the transport library's exception family
     * (the dependency points from there to here, not the reverse).
     */
    data class Failed(
        val cause: Throwable,
    ) : CloseCause
}
