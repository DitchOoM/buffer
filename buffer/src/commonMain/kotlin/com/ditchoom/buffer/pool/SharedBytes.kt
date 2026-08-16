package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.unwrapFully
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
@OptIn(ExperimentalAtomicApi::class)
class SharedBytes private constructor(
    private val adopted: PlatformBuffer,
) {
    /**
     * The raw buffer under [adopted]'s wrapper layers, captured once at [adopt].
     *
     * [view] slices **this**, not [adopted], and that is the whole reason this class exists.
     * `PooledBuffer.slice()` (and `TrackedSlice.slice()`) bumps a plain, non-atomic `refCount`
     * field before delegating, so two threads calling `view()` through a pooled wrapper would race
     * on that increment and lose one — the pooled chunk would then return to its pool while a live
     * view still aliased it. Slicing the unwrapped buffer sidesteps the wrapper's per-slice
     * tracking entirely: every plain buffer's `slice()` only *reads* the parent's position/limit
     * and allocates a new view object (verified on `HeapJvmBuffer`/`DirectJvmBuffer` →
     * `ByteBuffer.slice()`, `FfmBuffer` → `MemorySegment.asSlice`, `NativeBuffer`/`ByteArrayBuffer`
     * → offset arithmetic, `MutableDataBuffer` → pointer arithmetic, `JsBuffer` → `subarray`,
     * `LinearBuffer` → base-offset arithmetic), so concurrent slicing of a buffer whose cursor
     * never moves is race-free.
     *
     * Bypassing the wrapper's own count is deliberate and safe here because `SharedBytes`' atomic
     * [refCount] is the sole authority on lifetime. What gets released at zero is still [adopted],
     * the *wrapper* — so a pooled buffer goes back to its pool instead of being freed outright —
     * and it is released exactly once, by the single thread whose [release] drives the count to
     * zero.
     *
     * Invariant: nothing may move [adopted]'s (and therefore this buffer's) cursor after [adopt].
     * Views are slices of a stable `position..limit` window.
     */
    private val storage: ReadBuffer = adopted.unwrapFully()

    /**
     * Live reference count. Atomic because references are taken and dropped from unrelated
     * connection coroutines, which may be on different threads.
     */
    private val refCount = AtomicInt(1)

    /** Total readable bytes (every [view] starts with exactly this many remaining). */
    val size: Int = storage.remaining()

    /** Adds a reference. Throws [IllegalStateException] if the count already reached zero. */
    fun retain(): SharedBytes {
        // CAS loop rather than a blind increment: incrementing from zero would resurrect storage
        // that has already been freed/returned to its pool, and hand out views onto memory some
        // other acquirer now owns. A freed SharedBytes stays freed.
        while (true) {
            val current = refCount.load()
            check(current > 0) { "SharedBytes.retain() after the last reference was released" }
            if (refCount.compareAndSet(current, current + 1)) return this
        }
    }

    /**
     * Drops a reference; frees the adopted buffer at zero.
     * Throws [IllegalStateException] on over-release.
     */
    fun release() {
        while (true) {
            val current = refCount.load()
            check(current > 0) { "SharedBytes.release() called more times than it was retained" }
            if (refCount.compareAndSet(current, current - 1)) {
                // Exactly one thread observes the 1 -> 0 transition, so the free happens once.
                // freeNativeMemory() is the buffer's own discipline: a pooled buffer returns to
                // its pool, a deterministic buffer frees native memory, a GC-managed buffer no-ops.
                if (current == 1) adopted.freeNativeMemory()
                return
            }
        }
    }

    /**
     * A read-only slice with an independent cursor over the shared storage.
     * The caller must hold an undropped reference for the view's whole lifetime.
     */
    fun view(): ReadBuffer {
        // Guards view *creation* only. Per the class contract a view handed out while the count
        // was positive is not individually tracked afterwards; the caller's reference is what
        // keeps it valid.
        check(refCount.load() > 0) { "SharedBytes.view() after the last reference was released" }
        return storage.slice()
    }

    companion object {
        /**
         * Takes ownership of [buffer] (already `resetForRead()`) and returns a `SharedBytes`
         * holding the creator's single reference.
         */
        fun adopt(buffer: PlatformBuffer): SharedBytes = SharedBytes(buffer)
    }
}
