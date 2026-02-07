package com.ditchoom.buffer.pool

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ManagedMemoryAccess
import com.ditchoom.buffer.NativeMemoryAccess
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
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

    override fun acquire(minSize: Int): PooledBuffer {
        totalAllocations.incrementAndGet()
        val size = maxOf(minSize, defaultBufferSize)

        // Try to pop from stack (lock-free)
        val buffer = pop()

        return if (buffer != null && buffer.capacity >= size) {
            poolHits.incrementAndGet()
            buffer.resetForWrite()
            LockFreePooledBuffer(buffer, this)
        } else {
            poolMisses.incrementAndGet()
            val newBuffer = PlatformBuffer.allocate(size, allocationZone, byteOrder)
            LockFreePooledBuffer(newBuffer, this)
        }
    }

    override fun release(buffer: PooledBuffer) {
        if (buffer !is LockFreePooledBuffer) return

        // Only push if under max size (check first to avoid unnecessary work)
        if (poolSize.value < maxPoolSize) {
            buffer.inner.resetForWrite()
            if (push(buffer.inner)) {
                // Update peak if needed
                val currentSize = poolSize.value
                updatePeak(currentSize)
            } else {
                // CAS push failed because pool became full - free the buffer
                buffer.inner.freeNativeMemory()
            }
        } else {
            buffer.inner.freeNativeMemory()
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

/**
 * Pooled buffer wrapper for lock-free pool.
 */
internal class LockFreePooledBuffer(
    val inner: PlatformBuffer,
    private val pool: BufferPool,
) : PooledBuffer {
    override val capacity: Int get() = inner.capacity
    override val byteOrder: ByteOrder get() = inner.byteOrder

    override fun release() = pool.release(this)

    // Delegate memory access to inner buffer
    override val nativeMemoryAccess: NativeMemoryAccess?
        get() = inner as? NativeMemoryAccess

    override val managedMemoryAccess: ManagedMemoryAccess?
        get() = inner as? ManagedMemoryAccess

    // Delegate ReadBuffer
    override fun resetForRead() = inner.resetForRead()

    override fun readByte(): Byte = inner.readByte()

    override fun get(index: Int): Byte = inner.get(index)

    override fun readByteArray(size: Int): ByteArray = inner.readByteArray(size)

    override fun readShort(): Short = inner.readShort()

    override fun getShort(index: Int): Short = inner.getShort(index)

    override fun readInt(): Int = inner.readInt()

    override fun getInt(index: Int): Int = inner.getInt(index)

    override fun readLong(): Long = inner.readLong()

    override fun getLong(index: Int): Long = inner.getLong(index)

    override fun readFloat(): Float = inner.readFloat()

    override fun getFloat(index: Int): Float = inner.getFloat(index)

    override fun readDouble(): Double = inner.readDouble()

    override fun getDouble(index: Int): Double = inner.getDouble(index)

    override fun readString(
        length: Int,
        charset: Charset,
    ): String = inner.readString(length, charset)

    override fun slice(): ReadBuffer = inner.slice()

    override fun limit(): Int = inner.limit()

    override fun position(): Int = inner.position()

    override fun position(newPosition: Int) = inner.position(newPosition)

    override fun setLimit(limit: Int) = inner.setLimit(limit)

    // Delegate WriteBuffer
    override fun resetForWrite() = inner.resetForWrite()

    override fun writeByte(byte: Byte): WriteBuffer {
        inner.writeByte(byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        inner.set(index, byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        inner.writeBytes(bytes, offset, length)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        inner.writeShort(short)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        inner.set(index, short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        inner.writeInt(int)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        inner.set(index, int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        inner.writeLong(long)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        inner.set(index, long)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        inner.writeFloat(float)
        return this
    }

    override fun set(
        index: Int,
        float: Float,
    ): WriteBuffer {
        inner.set(index, float)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        inner.writeDouble(double)
        return this
    }

    override fun set(
        index: Int,
        double: Double,
    ): WriteBuffer {
        inner.set(index, double)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        inner.writeString(text, charset)
        return this
    }

    override fun write(buffer: ReadBuffer) = inner.write(buffer)
}
