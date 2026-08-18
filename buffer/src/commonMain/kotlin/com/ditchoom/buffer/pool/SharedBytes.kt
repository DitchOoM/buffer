package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.use
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
     * Live reference count. Atomic because references are taken and dropped from unrelated
     * connection coroutines, which may be on different threads.
     */
    private val refCount = AtomicInt(1)

    /** Total readable bytes (every [withView] borrow starts with exactly this many remaining). */
    val size: Int = adopted.remaining()

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
     * Borrows a read-only view with an independent cursor for the duration of [block], and revokes
     * it on the way out.
     *
     * This is the only way to read shared bytes, and the scope is the safety mechanism rather than
     * a convention. The borrow is sliced **through** [adopted] rather than through unwrapped
     * storage, so it is a `TrackedSlice` that re-checks liveness on every read and holds a
     * reference on the pooled chunk while the block runs; [use] drops that reference on exit, so
     * nothing is pinned afterwards. A view smuggled out of [block] is therefore *revoked* — reading
     * it throws instead of silently aliasing whatever the pool handed the next acquirer.
     *
     * An earlier unscoped `view()` returned a plain slice of unwrapped storage. That slice is
     * non-owning, so `freeNativeMemory()` on it — and therefore `use {}` around it — was a silent
     * no-op: the API looked lifetime-managed and was not. Scoping it is what makes `use` mean
     * something here.
     *
     * Inline so [block] may suspend: a writer opens its borrow at the moment it writes, not when
     * it enqueues.
     */
    inline fun <R> withView(block: (ReadBuffer) -> R): R = borrowView().use { block(it) }

    /** Backing for [withView]; internal-but-published only because [withView] is inline. */
    @PublishedApi
    internal fun borrowView(): PlatformBuffer {
        check(refCount.load() > 0) { "SharedBytes.withView() after the last reference was released" }
        return adopted.slice()
    }

    companion object {
        /**
         * Takes ownership of [buffer] (already `resetForRead()`) and returns a `SharedBytes`
         * holding the creator's single reference.
         *
         * **[buffer] must not be written or have its cursor moved after this call.** Views are
         * slices of a stable `position..limit` window, and every consumer reads that window
         * concurrently; mutating it afterwards races every live view.
         *
         * **A pooled [buffer] must come from a [ThreadingMode.MultiThreaded] pool**, enforced
         * here. The whole point of this type is that the *last* reference may be dropped on any
         * thread, and that release returns the chunk to its pool — against
         * [ThreadingMode.SingleThreaded]'s unsynchronized freelist that is a silent corruption
         * (a mangled freelist, or one chunk handed to two owners). `BufferPool()` defaults to
         * single-threaded, so this is the easy mistake to make and the reason it fails loudly
         * here rather than sporadically at some later acquire.
         */
        fun adopt(buffer: PlatformBuffer): SharedBytes {
            val pool = poolOf(buffer)
            require(pool == null || pool.threadingMode == ThreadingMode.MultiThreaded) {
                "SharedBytes.adopt() requires a ThreadingMode.MultiThreaded BufferPool: shared " +
                    "references are released from arbitrary threads, and a SingleThreaded pool's " +
                    "freelist is not safe to return a chunk to off its owning thread."
            }
            return SharedBytes(buffer)
        }

        /**
         * The pool backing [buffer], found by walking the same wrapper layers [unwrapFully]
         * resolves through. `null` for unpooled buffers, which have no freelist to corrupt.
         */
        @Suppress("DEPRECATION")
        private fun poolOf(buffer: PlatformBuffer): BufferPool? {
            var current: ReadBuffer? = buffer
            var found: BufferPool? = null
            while (found == null && current != null) {
                // Each step either identifies the pool or descends one wrapper; `null` means the
                // walk bottomed out at a plain buffer that carries no pool identity.
                current =
                    when (val layer = current) {
                        // Specific wrappers first: both also satisfy `is PlatformBuffer`.
                        is PooledBuffer -> null.also { found = layer.pool }
                        is TrackedSlice -> null.also { found = layer.parentPool }
                        is PlatformBuffer -> layer.unwrap().takeIf { it !== layer }
                        else -> null
                    }
            }
            return found
        }
    }
}
