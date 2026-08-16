package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Immutable encoded bytes shared across concurrent consumers, freed exactly once.
 *
 * The primitive behind encode-once fan-out: one buffer of encoded bytes handed to N independent
 * writers, each reading through its own cursor, with the backing storage released when — and only
 * when — the last reference is dropped. [PooledBuffer]'s existing chunk-plus-slices refcount is
 * the single-owner ancestor of this type; `SharedBytes` exists because that count is deliberately
 * NOT thread-safe (the single-consumer hot path must not pay for atomics), while sharing across
 * connections requires an atomic count. The atomicity lives here, in the sharing wrapper, and
 * nowhere else.
 *
 * ## Reference protocol
 *
 * - [adopt] takes **ownership** of a fully-written buffer (call `resetForRead()` first) and starts
 *   the count at 1 — the creator's reference.
 * - [retain] adds a reference; call it when transferring the bytes to another owner (e.g. enqueueing
 *   onto another connection's outbound queue). Returns `this` for chaining.
 * - [release] drops a reference. At zero the adopted buffer is freed via its own discipline
 *   (`use`-equivalent: pooled buffers return to their pool, deterministic buffers free native
 *   memory, GC-managed buffers no-op). Releasing below zero throws — an accounting bug must fail
 *   loudly, not double-free.
 * - Every reference is released **exactly once**. In the outbound-writer integration this rides the
 *   writer's exactly-once loss accounting: a transferred reference is released either after the
 *   write completes or on the not-sent path — never both, never neither.
 *
 * ## Views
 *
 * [view] returns a read-only-typed slice with an **independent cursor** over the shared storage:
 * concurrent writers never contend on position/limit. A view is valid only while the caller holds
 * an undropped reference; it is not individually guarded (experimental v1 — the parent's liveness
 * check fails fast on the storage, not per-view).
 *
 * Thread safety: [retain]/[release] are safe from any thread. [view] is safe concurrently with
 * other [view] calls and reads.
 */
@ExperimentalFanoutApi
class SharedBytes private constructor(
    private val adopted: PlatformBuffer,
) {
    /** Total readable bytes (every [view] starts with exactly this many remaining). */
    val size: Int
        get() = TODO("implemented by the shared-send work")

    /** Adds a reference. Throws [IllegalStateException] if the count already reached zero. */
    fun retain(): SharedBytes = TODO("implemented by the shared-send work")

    /**
     * Drops a reference; frees the adopted buffer at zero.
     * Throws [IllegalStateException] on over-release.
     */
    fun release(): Unit = TODO("implemented by the shared-send work")

    /**
     * A read-only slice with an independent cursor over the shared storage.
     * The caller must hold an undropped reference for the view's whole lifetime.
     */
    fun view(): ReadBuffer = TODO("implemented by the shared-send work")

    companion object {
        /**
         * Takes ownership of [buffer] (already `resetForRead()`) and returns a `SharedBytes`
         * holding the creator's single reference.
         */
        fun adopt(buffer: PlatformBuffer): SharedBytes = TODO("implemented by the shared-send work")
    }
}
