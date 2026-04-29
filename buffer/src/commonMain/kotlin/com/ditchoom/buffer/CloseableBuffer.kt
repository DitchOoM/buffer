package com.ditchoom.buffer

/**
 * Marker interface for buffers that hold resources NOT managed by garbage collection.
 *
 * Buffers implementing this interface **must** be explicitly cleaned up via
 * [PlatformBuffer.freeNativeMemory] or the [use] extension to prevent resource leaks.
 * Buffers that do NOT implement this interface are GC-managed — cleanup is optional.
 *
 * ## Which buffers implement CloseableBuffer?
 *
 * | Platform | Buffer type | Implements CloseableBuffer? |
 * |----------|------------|-----------------------------|
 * | JVM 21+ (default) | FfmAutoBuffer (Arena.ofAuto) | No (GC-managed) |
 * | JVM 21+ (deterministic) | FfmBuffer (Arena.ofShared/ofConfined) | **Yes** (via `BufferFactory.deterministic()`) |
 * | JVM 9-20 (deterministic) | DeterministicDirectJvmBuffer | **Yes** (via `BufferFactory.deterministic()`) |
 * | JVM 8 (deterministic) | DeterministicUnsafeJvmBuffer | **Yes** (via `BufferFactory.deterministic()`) |
 * | JVM (default) | DirectJvmBuffer | No (GC fallback) |
 * | Android (deterministic) | DeterministicUnsafeJvmBuffer | **Yes** (via `BufferFactory.deterministic()`) |
 * | Android (default) | DirectJvmBuffer | No (GC-managed) |
 * | Apple | MutableDataBuffer (NSMutableData) | No (ARC-managed) |
 * | Linux (default) | ByteArrayBuffer | No (GC-managed) |
 * | Linux (deterministic) | NativeBuffer (malloc/free) | **Yes** (via `BufferFactory.deterministic()`) |
 * | WASM | LinearBuffer (linear memory) | **Yes** |
 * | JS | JsBuffer (Int8Array) | No (GC-managed) |
 *
 * ## Usage
 *
 * ```kotlin
 * val buffer = BufferFactory.Default.allocate(1024)
 * if (buffer is CloseableBuffer) {
 *     // Platform requires explicit cleanup
 *     buffer.use { /* ... */ }
 * }
 * // Or always safe — use {} works on all buffers:
 * BufferFactory.Default.allocate(1024).use { buf ->
 *     buf.writeInt(42)
 * }
 * ```
 */
interface CloseableBuffer {
    /**
     * Whether this buffer's native memory has been freed.
     * Once true, all read/write operations on this buffer will throw.
     */
    val isFreed: Boolean
}

/**
 * Executes [block] with this [PlatformBuffer] and ensures cleanup when the block completes.
 *
 * If the buffer implements [CloseableBuffer], [freeNativeMemory][PlatformBuffer.freeNativeMemory]
 * is called after the block. Otherwise this is a no-op on completion (the buffer is
 * GC-managed).
 *
 * This is the recommended way to use buffers when you don't know at compile time
 * whether cleanup is required.
 */
inline fun <R> PlatformBuffer.use(block: (PlatformBuffer) -> R): R {
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        if (this is CloseableBuffer) {
            if (exception == null) {
                freeNativeMemory()
            } else {
                try {
                    freeNativeMemory()
                } catch (closeException: Throwable) {
                    exception.addSuppressed(closeException)
                }
            }
        }
    }
}
