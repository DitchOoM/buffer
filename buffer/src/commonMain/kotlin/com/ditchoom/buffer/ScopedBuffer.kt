package com.ditchoom.buffer

/**
 * A high-performance buffer with deterministic cleanup.
 *
 * ScopedBuffer combines the full [ReadBuffer] and [WriteBuffer] interfaces with
 * guaranteed native memory access ([NativeMemoryAccess]). Unlike [PlatformBuffer],
 * which is GC-managed, ScopedBuffer uses direct memory allocation with explicit
 * cleanup when the parent [BufferScope] closes.
 *
 * ## Performance Characteristics
 *
 * ScopedBuffer is optimized for performance-critical paths:
 * - **Direct memory access**: No ByteBuffer overhead on JVM, no intermediate copies
 * - **Deterministic cleanup**: Memory freed immediately when scope closes, no GC pressure
 * - **Platform-optimized**: Uses FFM on JVM 21+, Unsafe on older JVMs, malloc on native
 *
 * ## Usage
 *
 * ScopedBuffer instances are created via [BufferScope.allocate] and are only valid
 * within that scope:
 *
 * ```kotlin
 * withScope { scope ->
 *     val buffer = scope.allocate(1024)
 *     buffer.writeInt(42)
 *     buffer.writeString("Hello")
 *     buffer.resetForRead()
 *
 *     val value = buffer.readInt()      // 42
 *     val text = buffer.readString(5)   // "Hello"
 *
 *     // Native address available for FFI/JNI
 *     val address = buffer.nativeAddress
 * } // buffer is automatically freed here
 * ```
 *
 * ## Safety
 *
 * While ScopedBuffer uses "unsafe" memory operations internally, the scoped API
 * makes it safe to use:
 * - Buffers cannot escape the scope (no memory leaks)
 * - Memory is always freed when the scope closes
 * - Invalid access after scope close will fail fast
 *
 * ## Platform Implementations
 *
 * | Platform | Backing | Operations |
 * |----------|---------|------------|
 * | JVM 21+  | FFM Arena | MemorySegment |
 * | JVM < 21 | Unsafe.allocateMemory | UnsafeMemory |
 * | Android  | Unsafe.allocateMemory | UnsafeMemory |
 * | Native   | malloc | CPointer |
 * | WASM     | MemoryAllocator | Pointer (LinearMemory) |
 * | JS       | ArrayBuffer (GC) | TypedArray |
 *
 * @see BufferScope for creating scoped buffers
 * @see withScope for the recommended entry point
 * @see PlatformBuffer for GC-managed buffers
 */
interface ScopedBuffer :
    ReadBuffer,
    WriteBuffer,
    NativeMemoryAccess {
    /**
     * The scope that owns this buffer.
     *
     * The buffer is only valid while this scope is open. Accessing the buffer
     * after the scope closes will result in undefined behavior or an exception.
     */
    val scope: BufferScope

    /**
     * The total capacity of this buffer in bytes.
     *
     * This is the maximum amount of data that can be stored in the buffer.
     * Unlike [remaining], capacity does not change as data is read or written.
     */
    val capacity: Int
}
