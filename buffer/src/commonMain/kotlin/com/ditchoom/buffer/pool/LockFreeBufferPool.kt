package com.ditchoom.buffer.pool

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.allocate
import kotlinx.atomicfu.atomic

/**
 * Thread-safe buffer pool using lock-free algorithms (Treiber stack).
 *
 * Uses Compare-And-Swap (CAS) operations for thread-safe access without locks.
 * Suitable for concurrent access from multiple threads/coroutines.
 *
 * For single-threaded use, prefer [SingleThreadedBufferPool] for better performance.
 */
internal class LockFreeBufferPool(
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val byteOrder: ByteOrder,
    private val allocationZone: AllocationZone,
) : BufferPool {
    // Treiber stack node
    private class Node(
        val buffer: PlatformBuffer,
        val next: Node?,
    )

    // Lock-free stack head using atomicfu
    private val head = atomic<Node?>(null)

    // Atomic size counter to avoid traversing the list
    private val poolSize = atomic(0)

    // Atomic statistics
    private val totalAllocations = atomic(0L)
    private val poolHits = atomic(0L)
    private val poolMisses = atomic(0L)
    private val peakPoolSize = atomic(0)

    override fun acquire(minSize: Int): ReadWriteBuffer {
        totalAllocations.incrementAndGet()
        val size = maxOf(minSize, defaultBufferSize)

        // Try to pop from stack (lock-free)
        val buffer = pop()

        val raw =
            if (buffer != null && buffer.capacity >= size) {
                poolHits.incrementAndGet()
                buffer.resetForWrite()
                buffer
            } else {
                poolMisses.incrementAndGet()
                PlatformBuffer.allocate(size, allocationZone, byteOrder)
            }
        return PooledBuffer(raw, this)
    }

    override fun release(buffer: ReadWriteBuffer) {
        // Unwrap PooledBuffer to store the raw PlatformBuffer in the pool
        val platformBuffer =
            when (buffer) {
                is PooledBuffer -> buffer.inner
                is PlatformBuffer -> buffer
                else -> return
            }

        // Only push if under max size (check first to avoid unnecessary work)
        if (poolSize.value < maxPoolSize) {
            if (push(platformBuffer)) {
                // Update peak if needed
                val currentSize = poolSize.value
                updatePeak(currentSize)
            } else {
                // CAS push failed because pool became full - free the buffer
                platformBuffer.freeNativeMemory()
            }
        } else {
            platformBuffer.freeNativeMemory()
        }
    }

    override fun stats(): PoolStats =
        PoolStats(
            totalAllocations = totalAllocations.value,
            poolHits = poolHits.value,
            poolMisses = poolMisses.value,
            currentPoolSize = poolSize.value,
            peakPoolSize = peakPoolSize.value,
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
            val currentSize = poolSize.value
            if (currentSize >= maxPoolSize) {
                return false
            }

            val oldHead = head.value
            val newNode = Node(buffer, oldHead)

            // CAS: if head hasn't changed, update it
            if (head.compareAndSet(oldHead, newNode)) {
                poolSize.incrementAndGet()
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
            val oldHead = head.value ?: return null
            val newHead = oldHead.next

            // CAS: if head hasn't changed, update it
            if (head.compareAndSet(oldHead, newHead)) {
                poolSize.decrementAndGet()
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
            val peak = peakPoolSize.value
            if (currentSize <= peak) return
            if (peakPoolSize.compareAndSet(peak, currentSize)) return
        }
    }
}
