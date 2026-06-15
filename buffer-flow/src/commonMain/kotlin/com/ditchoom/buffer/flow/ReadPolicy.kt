package com.ditchoom.buffer.flow

import kotlin.time.Duration

/**
 * How long a [ByteSource] read is allowed to wait, expressed as a *policy* rather than a
 * defaulted parameter value.
 *
 * The point of making this a [ByteSource] **val** (instead of a defaulted `read(timeout = 15s)`
 * parameter) is that a `val` can be *overridden per implementation* while a default parameter
 * value cannot. A persistent stream (e.g. a WebTransport stream) sets
 * `override val readPolicy = UntilClosed`; a request/response stream keeps [Bounded]. No magic
 * deadline can be silently inherited onto a stream that should never time out on read.
 */
sealed interface ReadPolicy {
    /** Bound each read by [deadline] — the request/response shape. */
    data class Bounded(
        val deadline: Duration,
    ) : ReadPolicy

    /**
     * Never time out on read; liveness is delegated to the transport's idle-timeout. The shape for
     * persistent streams where data may legitimately arrive at any time.
     */
    data object UntilClosed : ReadPolicy

    /** The deadline a no-arg [ByteSource.read] should use. [UntilClosed] maps to [Duration.INFINITE]. */
    fun toDeadline(): Duration =
        when (this) {
            is Bounded -> deadline
            UntilClosed -> Duration.INFINITE
        }
}

/**
 * The write-side mirror of [ReadPolicy]. A [ByteSink] carries a [ByteSink.writePolicy] val; its
 * no-arg [ByteSink.write] consults it. Unlike [ReadPolicy] (whose default is role-dependent),
 * [WritePolicy] defaults to [Bounded] everywhere — an infinite write is rarely wanted — but the
 * same symmetric, override-per-impl mechanism applies.
 */
sealed interface WritePolicy {
    /** Bound each write by [deadline]. */
    data class Bounded(
        val deadline: Duration,
    ) : WritePolicy

    /** Never time out on write; liveness delegated to the transport's idle-timeout. */
    data object UntilClosed : WritePolicy

    /** The deadline a no-arg [ByteSink.write] should use. [UntilClosed] maps to [Duration.INFINITE]. */
    fun toDeadline(): Duration =
        when (this) {
            is Bounded -> deadline
            UntilClosed -> Duration.INFINITE
        }
}
