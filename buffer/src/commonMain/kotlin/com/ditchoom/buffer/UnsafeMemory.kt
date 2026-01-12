package com.ditchoom.buffer

/**
 * Cross-platform API for direct memory operations.
 *
 * Provides low-level memory access for high-performance scenarios like:
 * - Custom serialization bypassing ByteBuffer overhead
 * - Native library interop (passing addresses to JNI/FFI)
 * - Memory-mapped file operations
 *
 * Platform implementations:
 * - JVM: sun.misc.Unsafe (Java 8-20), FFM MemorySegment (Java 21+)
 * - Android: sun.misc.Unsafe via reflection
 * - Apple/Native: CPointer + memcpy/memset
 * - WASM: kotlin.wasm.unsafe.Pointer
 * - JS: Not supported (isSupported = false)
 *
 * Example usage:
 * ```kotlin
 * val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
 * val address = buffer.nativeMemoryAccess!!.nativeAddress
 *
 * // Fast header parsing without ByteBuffer overhead
 * val magic = UnsafeMemory.getInt(address)
 * val version = UnsafeMemory.getShort(address + 4)
 * ```
 *
 * @see NativeMemoryAccess for getting buffer addresses
 */
expect object UnsafeMemory {
    /**
     * Whether unsafe memory operations are supported on this platform.
     *
     * Always false on JavaScript (no direct memory access).
     * May be false on platforms where Unsafe is not available.
     */
    val isSupported: Boolean

    /**
     * Reads a byte from the given memory address.
     *
     * @param address The memory address to read from
     * @return The byte value at the address
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun getByte(address: Long): Byte

    /**
     * Writes a byte to the given memory address.
     *
     * @param address The memory address to write to
     * @param value The byte value to write
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun putByte(
        address: Long,
        value: Byte,
    )

    /**
     * Reads a short (2 bytes) from the given memory address.
     * Uses native byte order.
     *
     * @param address The memory address to read from
     * @return The short value at the address
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun getShort(address: Long): Short

    /**
     * Writes a short (2 bytes) to the given memory address.
     * Uses native byte order.
     *
     * @param address The memory address to write to
     * @param value The short value to write
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun putShort(
        address: Long,
        value: Short,
    )

    /**
     * Reads an int (4 bytes) from the given memory address.
     * Uses native byte order.
     *
     * @param address The memory address to read from
     * @return The int value at the address
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun getInt(address: Long): Int

    /**
     * Writes an int (4 bytes) to the given memory address.
     * Uses native byte order.
     *
     * @param address The memory address to write to
     * @param value The int value to write
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun putInt(
        address: Long,
        value: Int,
    )

    /**
     * Reads a long (8 bytes) from the given memory address.
     * Uses native byte order.
     *
     * @param address The memory address to read from
     * @return The long value at the address
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun getLong(address: Long): Long

    /**
     * Writes a long (8 bytes) to the given memory address.
     * Uses native byte order.
     *
     * @param address The memory address to write to
     * @param value The long value to write
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun putLong(
        address: Long,
        value: Long,
    )

    /**
     * Copies memory from source address to destination address.
     *
     * @param srcAddress The source memory address
     * @param dstAddress The destination memory address
     * @param size The number of bytes to copy
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun copyMemory(
        srcAddress: Long,
        dstAddress: Long,
        size: Long,
    )

    /**
     * Sets all bytes in a memory region to a specific value.
     *
     * @param address The starting memory address
     * @param size The number of bytes to set
     * @param value The byte value to fill with
     * @throws UnsupportedOperationException if not supported on this platform
     */
    fun setMemory(
        address: Long,
        size: Long,
        value: Byte,
    )
}
