package com.ditchoom.buffer

/**
 * Interface for buffers that provide direct access to native memory.
 *
 * This enables zero-copy interop with native code, FFI, and other runtimes:
 * - **WASM**: Linear memory offset for JS interop via DataView
 * - **JVM**: DirectByteBuffer address for NIO/FFM
 * - **Apple**: Pointer address for C interop
 * - **JS**: ArrayBuffer byte offset for WebGL, fetch, etc.
 *
 * Example usage:
 * ```kotlin
 * val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
 * buffer.nativeMemoryAccess?.let { native ->
 *     val address = native.nativeAddress
 *     val size = native.nativeSize
 *     // Pass to native code
 * }
 * ```
 */
interface NativeMemoryAccess {
    /**
     * The native memory address or offset for this buffer.
     *
     * Platform-specific meanings:
     * - **WASM**: Offset in linear memory (use with `Pointer` or JS `DataView`)
     * - **JVM**: Direct buffer address (use with Unsafe or FFM `MemorySegment`)
     * - **Apple**: Pointer address (use with `CPointer`)
     * - **JS**: Byte offset within the ArrayBuffer
     */
    val nativeAddress: Long

    /**
     * The size of the native memory region in bytes.
     */
    val nativeSize: Int
}

/**
 * Returns this buffer's [NativeMemoryAccess] if it supports direct native memory access,
 * or null if it doesn't (e.g., heap-allocated buffers).
 *
 * This is useful for checking if a buffer can be passed to native code without copying.
 *
 * Example:
 * ```kotlin
 * val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
 * buffer.nativeMemoryAccess?.let { native ->
 *     // Buffer supports native access
 *     passToNativeCode(native.nativeAddress, native.nativeSize)
 * } ?: run {
 *     // Fallback: copy to a native buffer
 * }
 * ```
 */
val PlatformBuffer.nativeMemoryAccess: NativeMemoryAccess?
    get() = this as? NativeMemoryAccess

/**
 * Allocates a buffer with guaranteed native memory access.
 *
 * This is equivalent to `PlatformBuffer.allocate(size, AllocationZone.Direct)` but
 * makes the intent explicit and throws on platforms that don't support native memory.
 *
 * @throws UnsupportedOperationException on platforms without native memory support
 */
expect fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer
