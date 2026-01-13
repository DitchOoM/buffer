@file:Suppress("UNCHECKED_CAST")

package com.ditchoom.buffer

import sun.misc.Unsafe

/**
 * Unsafe-based BufferScope implementation for JVM < 21 and Android.
 *
 * Uses sun.misc.Unsafe for direct memory allocation and deallocation.
 * Tracks all allocations to ensure cleanup on close.
 */
class UnsafeBufferScope : BufferScope {
    private val allocations = mutableListOf<Long>()
    private var open = true

    override val isOpen: Boolean get() = open

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(open) { "BufferScope is closed" }
        val address = UnsafeAllocator.allocateMemory(size.toLong())
        allocations.add(address)
        return UnsafeScopedBuffer(this, address, size, byteOrder)
    }

    override fun allocateAligned(
        size: Int,
        alignment: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(open) { "BufferScope is closed" }
        require(alignment > 0 && (alignment and (alignment - 1)) == 0) {
            "Alignment must be a positive power of 2, got: $alignment"
        }
        // Allocate extra for alignment
        val totalSize = size.toLong() + alignment - 1
        val rawAddress = UnsafeAllocator.allocateMemory(totalSize)
        allocations.add(rawAddress)

        // Align the address
        val alignedAddress = (rawAddress + alignment - 1) and (alignment - 1).inv().toLong()
        return UnsafeScopedBuffer(this, alignedAddress, size, byteOrder)
    }

    override fun close() {
        if (open) {
            open = false
            for (address in allocations) {
                UnsafeAllocator.freeMemory(address)
            }
            allocations.clear()
        }
    }
}

/**
 * Internal accessor for sun.misc.Unsafe memory operations.
 */
internal object UnsafeAllocator {
    private val unsafe: Unsafe =
        try {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        } catch (e: Exception) {
            throw UnsupportedOperationException(
                "UnsafeBufferScope requires sun.misc.Unsafe which is not available",
                e,
            )
        }

    fun allocateMemory(size: Long): Long = unsafe.allocateMemory(size)

    fun freeMemory(address: Long) = unsafe.freeMemory(address)
}
