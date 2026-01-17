package com.ditchoom.buffer

/**
 * A scope that manages the lifetime of [ScopedBuffer] instances.
 *
 * BufferScope provides deterministic memory management for high-performance buffer
 * operations. All buffers allocated from a scope are automatically freed when the
 * scope is closed, eliminating memory leaks and reducing GC pressure.
 *
 * ## Usage
 *
 * Use [withScope] to create a scope and allocate buffers:
 *
 * ```kotlin
 * withScope { scope ->
 *     val input = scope.allocate(8192)
 *     val output = scope.allocate(32768)
 *
 *     // Read data into input buffer
 *     channel.read(input)
 *     input.resetForRead()
 *
 *     // Process data...
 *     processData(input, output)
 *
 * } // Both input and output are automatically freed here
 * ```
 *
 * ## Platform Implementations
 *
 * | Platform | Implementation | Cleanup |
 * |----------|---------------|---------|
 * | JVM 21+  | FFM Arena.ofConfined() | Arena.close() |
 * | JVM < 21 | Unsafe.allocateMemory() tracking | Unsafe.freeMemory() |
 * | Android  | Unsafe.allocateMemory() tracking | Unsafe.freeMemory() |
 * | Native   | malloc() tracking | free() |
 * | WASM     | MemoryAllocator | LinearMemory deallocation |
 * | JS       | No-op (GC managed) | GC handles cleanup |
 *
 * ## Thread Safety
 *
 * BufferScope is NOT thread-safe by default. Each thread should use its own scope.
 * For multi-threaded scenarios, use separate scopes per thread or external synchronization.
 *
 * @see ScopedBuffer for buffers created by this scope
 * @see withScope for the recommended entry point
 */
interface BufferScope : AutoCloseable {
    /**
     * Allocates a new [ScopedBuffer] of the specified size.
     *
     * The buffer is owned by this scope and will be freed when the scope closes.
     * The buffer is initialized with:
     * - position = 0
     * - limit = capacity = size
     * - All bytes are uninitialized (may contain garbage)
     *
     * @param size The capacity of the buffer in bytes
     * @param byteOrder The byte order for multi-byte operations (default: BIG_ENDIAN)
     * @return A new ScopedBuffer ready for writing
     * @throws IllegalStateException if the scope is already closed
     * @throws OutOfMemoryError if allocation fails
     */
    fun allocate(
        size: Int,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): ScopedBuffer

    /**
     * Allocates a new [ScopedBuffer] with the specified alignment.
     *
     * Aligned allocation is useful for:
     * - SIMD operations that require specific alignment (e.g., 16, 32, or 64 bytes)
     * - DMA transfers that require page alignment
     * - Interop with native code that expects aligned buffers
     *
     * Not all platforms support alignment; those that don't will ignore the
     * alignment parameter and allocate normally.
     *
     * @param size The capacity of the buffer in bytes
     * @param alignment The required alignment in bytes (must be a power of 2)
     * @param byteOrder The byte order for multi-byte operations (default: BIG_ENDIAN)
     * @return A new ScopedBuffer with the requested alignment (best effort)
     * @throws IllegalStateException if the scope is already closed
     * @throws IllegalArgumentException if alignment is not a power of 2
     * @throws OutOfMemoryError if allocation fails
     */
    fun allocateAligned(
        size: Int,
        alignment: Int,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): ScopedBuffer = allocate(size, byteOrder) // Default: ignore alignment

    /**
     * Whether this scope is still open and can allocate buffers.
     *
     * Once closed, a scope cannot be reopened and all its buffers are invalid.
     */
    val isOpen: Boolean

    /**
     * Closes this scope and frees all allocated buffers.
     *
     * After calling close:
     * - [isOpen] returns false
     * - [allocate] throws [IllegalStateException]
     * - All previously allocated buffers become invalid
     *
     * This method is idempotent; calling it multiple times has no additional effect.
     */
    override fun close()
}

/**
 * Creates a new [BufferScope], executes the given [block] with it, and ensures
 * the scope is closed when the block completes (normally or exceptionally).
 *
 * This is the recommended way to use scoped buffers:
 *
 * ```kotlin
 * val result = withScope { scope ->
 *     val buffer = scope.allocate(1024)
 *     buffer.writeInt(42)
 *     buffer.resetForRead()
 *     buffer.readInt()
 * } // scope and all buffers are automatically freed
 * println(result) // 42
 * ```
 *
 * ## Performance
 *
 * The scope and its buffers use platform-optimized allocation:
 * - JVM 21+: FFM Arena with MemorySegment operations
 * - JVM < 21: Unsafe direct memory allocation
 * - Native: malloc/free with direct pointer operations
 * - WASM: LinearMemory allocation
 *
 * ## Exception Safety
 *
 * If the block throws an exception, the scope is still closed and all buffers
 * are freed before the exception propagates.
 *
 * @param block The code to execute with the scope
 * @return The result of the block
 */
expect inline fun <T> withScope(block: (BufferScope) -> T): T
