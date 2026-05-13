package com.ditchoom.buffer.codec

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Bounded lock-free free-list of recyclable [GrowableWriteBuffer] wrappers.
 *
 * Every `@FramedBy` encode emit reaches [FramedEncoder.encode], which used
 * to allocate a fresh [GrowableWriteBuffer] for each call. In the
 * pooled-factory hot path that translated to one extra short-lived heap
 * object per packet — multiplied by every write on the wire. Recycling the
 * wrapper keeps the FramedEncoder slack-region design but eliminates this
 * allocation from the steady-state path.
 *
 * Treiber stack with a maximum size cap so a runaway encode burst can't
 * indefinitely grow the pool. Lock-free CAS to stay correct under
 * concurrent encode callers; the encode path itself doesn't pin a single
 * thread on multi-platform.
 *
 * Uses `kotlin.concurrent.atomics` (stable since Kotlin 2.1) rather than
 * the atomicfu plugin so the bytecode is portable to Android without
 * a build-time transform step that would otherwise leave `kotlinx.atomicfu`
 * runtime classes referenced from the dex'd APK.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object GrowableWriteBufferPool {
    private const val MAX_POOL_SIZE: Int = 32

    private class Node(
        val wrapper: GrowableWriteBuffer,
        val next: Node?,
    )

    private val head = AtomicReference<Node?>(null)
    private val size = AtomicInt(0)

    /** Pops a recycled wrapper, or returns a fresh one if the pool is empty. */
    fun acquire(): GrowableWriteBuffer {
        while (true) {
            val current = head.load() ?: return GrowableWriteBuffer()
            if (head.compareAndSet(current, current.next)) {
                size.update { it - 1 }
                return current.wrapper
            }
        }
    }

    /**
     * Returns a detached wrapper to the pool. Caller is responsible for
     * invoking [GrowableWriteBuffer.detach] before releasing — releasing an
     * attached wrapper would pin its inner buffer.
     */
    fun release(wrapper: GrowableWriteBuffer) {
        if (size.load() >= MAX_POOL_SIZE) return
        while (true) {
            val current = head.load()
            val node = Node(wrapper, current)
            if (head.compareAndSet(current, node)) {
                size.update { it + 1 }
                return
            }
        }
    }

    private inline fun AtomicInt.update(transform: (Int) -> Int) {
        while (true) {
            val current = load()
            if (compareAndSet(current, transform(current))) return
        }
    }
}
