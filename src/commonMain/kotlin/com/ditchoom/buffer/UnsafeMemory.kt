package com.ditchoom.buffer

/**
 * Low-level unsafe memory operations for direct memory access.
 * Provides native-speed memory operations on all platforms.
 *
 * Platform implementations:
 * - JVM/Android: sun.misc.Unsafe
 * - Apple/Linux: kotlinx.cinterop nativeHeap with typed pointers
 * - JS: ArrayBuffer with DataView
 * - WASM: Linear memory with Pointer load/store
 *
 * Warning: Memory allocated with [allocate] must be freed with [free].
 * Failure to do so will result in memory leaks.
 */
expect object UnsafeMemory {
    /**
     * Allocates a block of uninitialized memory.
     * @param size The number of bytes to allocate
     * @return The address of the allocated memory (platform-specific representation as Long)
     */
    fun allocate(size: Int): Long

    /**
     * Frees memory previously allocated with [allocate].
     * @param address The address returned by [allocate]
     */
    fun free(address: Long)

    /**
     * Loads a byte from the given address.
     */
    fun getByte(
        address: Long,
        offset: Int,
    ): Byte

    /**
     * Stores a byte at the given address.
     */
    fun putByte(
        address: Long,
        offset: Int,
        value: Byte,
    )

    /**
     * Loads a short (2 bytes) from the given address in native byte order.
     */
    fun getShort(
        address: Long,
        offset: Int,
    ): Short

    /**
     * Stores a short (2 bytes) at the given address in native byte order.
     */
    fun putShort(
        address: Long,
        offset: Int,
        value: Short,
    )

    /**
     * Loads an int (4 bytes) from the given address in native byte order.
     */
    fun getInt(
        address: Long,
        offset: Int,
    ): Int

    /**
     * Stores an int (4 bytes) at the given address in native byte order.
     */
    fun putInt(
        address: Long,
        offset: Int,
        value: Int,
    )

    /**
     * Loads a long (8 bytes) from the given address in native byte order.
     */
    fun getLong(
        address: Long,
        offset: Int,
    ): Long

    /**
     * Stores a long (8 bytes) at the given address in native byte order.
     */
    fun putLong(
        address: Long,
        offset: Int,
        value: Long,
    )

    /**
     * Loads a float (4 bytes) from the given address in native byte order.
     */
    fun getFloat(
        address: Long,
        offset: Int,
    ): Float

    /**
     * Stores a float (4 bytes) at the given address in native byte order.
     */
    fun putFloat(
        address: Long,
        offset: Int,
        value: Float,
    )

    /**
     * Loads a double (8 bytes) from the given address in native byte order.
     */
    fun getDouble(
        address: Long,
        offset: Int,
    ): Double

    /**
     * Stores a double (8 bytes) at the given address in native byte order.
     */
    fun putDouble(
        address: Long,
        offset: Int,
        value: Double,
    )

    /**
     * Copies bytes from native memory to a ByteArray.
     */
    fun copyToArray(
        address: Long,
        offset: Int,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    )

    /**
     * Copies bytes from a ByteArray to native memory.
     */
    fun copyFromArray(
        src: ByteArray,
        srcOffset: Int,
        address: Long,
        offset: Int,
        length: Int,
    )

    /**
     * Sets a region of memory to zero.
     */
    fun zeroMemory(
        address: Long,
        offset: Int,
        length: Int,
    )

    /**
     * The native byte order of the platform.
     */
    val nativeByteOrder: ByteOrder
}
