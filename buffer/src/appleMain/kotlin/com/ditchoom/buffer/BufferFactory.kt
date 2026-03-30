@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        @OptIn(UnsafeNumber::class)
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            require(size >= 0) { "Buffer size must be non-negative, got $size" }
            val data =
                NSMutableData.create(length = size.convert())
                    ?: error("Failed to allocate NSMutableData of $size bytes")
            return MutableDataBuffer(data, byteOrder = byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

internal actual val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(ByteArray(size), byteOrder = byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

internal actual val sharedBufferFactory: BufferFactory = defaultBufferFactory

// Apple buffers use ARC (MutableDataBuffer) — already deterministic
private val deterministicFactoryInstance: BufferFactory = defaultBufferFactory

internal actual fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory = deterministicFactoryInstance

/**
 * Wraps an existing NSData in a read-only buffer (zero-copy).
 *
 * The buffer shares memory with the original NSData. This is efficient for
 * reading data from Apple APIs without copying.
 *
 * @param data The NSData to wrap
 * @param byteOrder The byte order for multi-byte operations
 * @return An [NSDataBuffer] providing read-only access to the data
 */
fun PlatformBuffer.Companion.wrapReadOnly(
    data: NSData,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): ReadBuffer = NSDataBuffer(data, byteOrder)

/**
 * Wraps an existing NSData in a mutable buffer by creating a copy.
 *
 * This creates a new NSMutableData with a copy of the data to provide write operations.
 * For zero-copy read-only access, use [wrapReadOnly] instead.
 *
 * @param data The NSData to wrap
 * @param byteOrder The byte order for multi-byte operations
 * @return A [MutableDataBuffer] wrapping a mutable copy of the data
 */
@OptIn(UnsafeNumber::class)
fun PlatformBuffer.Companion.wrap(
    data: NSData,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): MutableDataBuffer {
    val mutableData = NSMutableData.create(data)
    return MutableDataBuffer(mutableData, byteOrder)
}

/**
 * Wraps an existing NSMutableData in a buffer (zero-copy).
 *
 * The buffer shares memory with the original NSMutableData.
 * Modifications to the buffer will be visible in the original data and vice versa.
 *
 * @param data The NSMutableData to wrap
 * @param byteOrder The byte order for multi-byte operations
 * @return A [MutableDataBuffer] wrapping the data
 */
fun PlatformBuffer.Companion.wrap(
    data: NSMutableData,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): MutableDataBuffer = MutableDataBuffer(data, byteOrder)

/**
 * Allocates a buffer with guaranteed native memory access (MutableDataBuffer).
 * Uses NSMutableData which provides native pointer access via [NativeMemoryAccess].
 */
@OptIn(UnsafeNumber::class)
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    require(size >= 0) { "Buffer size must be non-negative, got $size" }
    val data =
        NSMutableData.create(length = size.convert())
            ?: error("Failed to allocate NSMutableData of $size bytes")
    return MutableDataBuffer(data, byteOrder = byteOrder)
}

/**
 * Allocates a buffer with shared memory support.
 * On Apple, falls back to direct allocation (no cross-process shared memory API exposed).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = BufferFactory.Default.allocate(size, byteOrder)

@OptIn(UnsafeNumber::class)
actual fun PlatformBuffer.Companion.wrapNativeAddress(
    address: Long,
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    val ptr =
        address.toCPointer<ByteVar>()
            ?: throw IllegalArgumentException("Cannot wrap null address (0)")
    // Use NSMutableData constructor (alloc+init) rather than factory method (create)
    // to ensure the mutable data wraps the pointer without copying.
    // freeWhenDone=false: the buffer does NOT own this memory.
    val data =
        NSMutableData(
            bytesNoCopy = ptr,
            length = size.convert(),
            freeWhenDone = false,
        )
    val buffer = MutableDataBuffer(data, byteOrder)
    // Verify zero-copy: mutableBytes must return the same pointer we passed in
    check(buffer.nativeAddress == address) {
        "NSMutableData did not preserve the pointer (expected $address, got ${buffer.nativeAddress}). " +
            "wrapNativeAddress requires zero-copy pointer sharing."
    }
    return buffer
}
