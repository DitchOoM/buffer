package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Thread-safe buffer pool using lock-free algorithms (Treiber stack).
 *
 * Uses Compare-And-Swap (CAS) operations for thread-safe access without locks.
 * Suitable for concurrent access from multiple threads/coroutines.
 *
 * For single-threaded use, prefer [SingleThreadedBufferPool] for better performance.
 *
 * Uses `kotlin.concurrent.atomics` (stdlib) rather than the atomicfu plugin so the
 * bytecode is portable to Android without a build-time transform step — the atomicfu
 * transform didn't run for the `-android` variant, leaving `kotlinx.atomicfu.AtomicFU`
 * referenced at runtime with no declared dependency (NoClassDefFoundError). Mirrors
 * [com.ditchoom.buffer.codec.GrowableWriteBufferPool].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LockFreeBufferPool(
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val factory: BufferFactory,
) : BufferPool {
    // Treiber stack node
    private class Node(
        val buffer: PlatformBuffer,
        val next: Node?,
    )

    // Lock-free stack head
    private val head = AtomicReference<Node?>(null)

    // Atomic size counter to avoid traversing the list
    private val poolSize = AtomicInt(0)

    // Atomic statistics
    private val totalAllocations = AtomicLong(0L)
    private val poolHits = AtomicLong(0L)
    private val poolMisses = AtomicLong(0L)
    private val peakPoolSize = AtomicInt(0)

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = acquire(size)
        if (buffer is PlatformBuffer && buffer.byteOrder == byteOrder) return buffer
        release(buffer)
        return factory.allocate(size, byteOrder)
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = factory.wrap(array, byteOrder)

    override fun acquire(minSize: Int): ReadWriteBuffer {
        totalAllocations.update { it + 1 }
        val size = maxOf(minSize, defaultBufferSize)

        // Try to pop from stack (lock-free)
        val buffer = pop()

        val raw =
            if (buffer != null && buffer.capacity >= size) {
                poolHits.update { it + 1 }
                buffer.resetForWrite()
                buffer
            } else {
                poolMisses.update { it + 1 }
                // A too-small popped buffer was removed from the pool; free its native
                // memory before allocating fresh, otherwise it leaks (Arena.ofShared
                // never closes, FfmAutoBuffer waits on GC).
                buffer?.freeNativeMemory()
                factory.allocate(size)
            }
        return PooledBuffer(raw, this)
    }

    override fun release(buffer: ReadWriteBuffer) {
        val raw =
            when (buffer) {
                is PooledBuffer -> {
                    require(buffer.pool === this) {
                        "Cannot release a buffer to a different pool than the one it was acquired from"
                    }
                    buffer.inner
                }
                is PlatformBuffer -> buffer
                else -> return
            }

        // Only push if under max size (check first to avoid unnecessary work)
        if (poolSize.load() < maxPoolSize) {
            if (push(raw)) {
                // Update peak if needed
                val currentSize = poolSize.load()
                updatePeak(currentSize)
            } else {
                // CAS push failed because pool became full - free the buffer
                raw.freeNativeMemory()
            }
        } else {
            raw.freeNativeMemory()
        }
    }

    override fun stats(): PoolStats =
        PoolStats(
            totalAllocations = totalAllocations.load(),
            poolHits = poolHits.load(),
            poolMisses = poolMisses.load(),
            currentPoolSize = poolSize.load(),
            peakPoolSize = peakPoolSize.load(),
        )

    override fun clear() {
        // Pop all elements and free their native memory
        while (true) {
            val buffer = pop() ?: break
            buffer.freeNativeMemory()
        }
    }

    /**
     * Lock-free push onto Treiber stack.
     * Returns true if pushed, false if pool is full.
     */
    private fun push(buffer: PlatformBuffer): Boolean {
        while (true) {
            // Check size limit before attempting push
            val currentSize = poolSize.load()
            if (currentSize >= maxPoolSize) {
                return false
            }

            val oldHead = head.load()
            val newNode = Node(buffer, oldHead)

            // CAS: if head hasn't changed, update it
            if (head.compareAndSet(oldHead, newNode)) {
                poolSize.update { it + 1 }
                return true
            }
            // CAS failed, retry
        }
    }

    /**
     * Lock-free pop from Treiber stack.
     * Returns the buffer or null if stack is empty.
     */
    private fun pop(): PlatformBuffer? {
        while (true) {
            val oldHead = head.load() ?: return null
            val newHead = oldHead.next

            // CAS: if head hasn't changed, update it
            if (head.compareAndSet(oldHead, newHead)) {
                poolSize.update { it - 1 }
                return oldHead.buffer
            }
            // CAS failed, retry
        }
    }

    /**
     * Atomically update peak pool size if current is larger.
     */
    private fun updatePeak(currentSize: Int) {
        while (true) {
            val peak = peakPoolSize.load()
            if (currentSize <= peak) return
            if (peakPoolSize.compareAndSet(peak, currentSize)) return
        }
    }

    private inline fun AtomicInt.update(transform: (Int) -> Int) {
        while (true) {
            val current = load()
            if (compareAndSet(current, transform(current))) return
        }
    }

    private inline fun AtomicLong.update(transform: (Long) -> Long) {
        while (true) {
            val current = load()
            if (compareAndSet(current, transform(current))) return
        }
    }
}
