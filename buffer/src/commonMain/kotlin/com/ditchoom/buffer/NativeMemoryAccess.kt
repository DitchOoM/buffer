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
     *
     * Uses Long for compatibility with Java FFM (Foreign Function & Memory API)
     * which uses long for memory segment sizes.
     */
    val nativeSize: Long
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
    get() = unwrap() as? NativeMemoryAccess

/**
 * Extension for ReadBuffer to access native memory if available.
 * Unwraps pooled buffers to reach the underlying platform buffer.
 */
val ReadBuffer.nativeMemoryAccess: NativeMemoryAccess?
    get() {
        if (this is NativeMemoryAccess) return this
        if (this is PlatformBuffer) return unwrap() as? NativeMemoryAccess
        return null
    }

/**
 * Extension for WriteBuffer to access native memory if available.
 * Unwraps pooled buffers to reach the underlying platform buffer.
 */
val WriteBuffer.nativeMemoryAccess: NativeMemoryAccess?
    get() = ((this as? PlatformBuffer)?.unwrap() ?: this) as? NativeMemoryAccess

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

/**
 * Interface for buffers backed by a Kotlin-managed ByteArray.
 *
 * This enables zero-copy access to the underlying ByteArray for:
 * - Serialization libraries that work directly with ByteArray
 * - Interop with APIs expecting ByteArray input
 * - Efficient bulk data transfer without intermediate copies
 *
 * Example usage:
 * ```kotlin
 * val buffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)
 * buffer.managedMemoryAccess?.let { managed ->
 *     val array = managed.backingArray
 *     val offset = managed.arrayOffset
 *     // Use array directly for serialization, etc.
 * }
 * ```
 */
interface ManagedMemoryAccess {
    /**
     * The underlying ByteArray backing this buffer.
     *
     * Modifications to this array are reflected in the buffer and vice versa.
     * Use [arrayOffset] to find the start of valid data within the array.
     */
    val backingArray: ByteArray

    /**
     * The offset within [backingArray] where this buffer's data begins.
     *
     * For most buffers this is 0, but slices may have non-zero offsets.
     */
    val arrayOffset: Int
}

/**
 * Returns this buffer's [ManagedMemoryAccess] if it's backed by a Kotlin ByteArray,
 * or null if it uses native memory (e.g., direct buffers, linear memory).
 *
 * Example:
 * ```kotlin
 * val buffer = PlatformBuffer.wrap(byteArrayOf(1, 2, 3))
 * buffer.managedMemoryAccess?.let { managed ->
 *     // Direct access to backing array
 *     managed.backingArray[managed.arrayOffset] = 42
 * }
 * ```
 */
val PlatformBuffer.managedMemoryAccess: ManagedMemoryAccess?
    get() = unwrap() as? ManagedMemoryAccess

/**
 * Extension for ReadBuffer to access managed memory if available.
 * Unwraps pooled buffers to reach the underlying platform buffer.
 */
val ReadBuffer.managedMemoryAccess: ManagedMemoryAccess?
    get() {
        if (this is ManagedMemoryAccess) return this
        if (this is PlatformBuffer) return unwrap() as? ManagedMemoryAccess
        return null
    }

/**
 * Extension for WriteBuffer to access managed memory if available.
 * Unwraps pooled buffers to reach the underlying platform buffer.
 */
val WriteBuffer.managedMemoryAccess: ManagedMemoryAccess?
    get() = ((this as? PlatformBuffer)?.unwrap() ?: this) as? ManagedMemoryAccess

/**
 * Interface for buffers backed by shared memory that can be accessed across
 * processes or threads.
 *
 * Platform-specific implementations:
 * - **Android**: Uses `SharedMemory` (API 27+) for zero-copy IPC via Parcelable
 * - **JS**: Uses `SharedArrayBuffer` for cross-worker communication
 * - **JVM/Apple/WASM/Linux**: Falls back to Direct allocation (no cross-process sharing)
 *
 * Example usage:
 * ```kotlin
 * val buffer = PlatformBuffer.allocateShared(1024)
 * if (buffer.sharedMemoryAccess?.isShared == true) {
 *     // Buffer can be shared across processes/workers
 * }
 * ```
 */
interface SharedMemoryAccess {
    /**
     * Whether this buffer is backed by actual shared memory.
     *
     * Returns `true` if the buffer uses platform-specific shared memory:
     * - Android: SharedMemory (API 27+)
     * - JS: SharedArrayBuffer (requires CORS headers)
     *
     * Returns `false` if shared memory allocation failed and fell back to
     * direct allocation, or if the platform doesn't support shared memory.
     */
    val isShared: Boolean
}

/**
 * Returns this buffer's [SharedMemoryAccess] if it was allocated with shared memory support,
 * or null if it wasn't.
 *
 * Note: Even if non-null, check [SharedMemoryAccess.isShared] to verify if the buffer
 * is actually backed by shared memory (allocation may have fallen back to direct).
 */
val PlatformBuffer.sharedMemoryAccess: SharedMemoryAccess?
    get() = this as? SharedMemoryAccess

/**
 * Allocates a buffer with shared memory if supported by the platform.
 *
 * Shared memory enables zero-copy data sharing:
 * - **Android**: Cross-process IPC via Parcelable (API 27+)
 * - **JS**: Cross-worker communication via SharedArrayBuffer
 *
 * If shared memory is not available, falls back to direct allocation.
 * Check [SharedMemoryAccess.isShared] on the returned buffer to verify.
 *
 * @param size The buffer size in bytes
 * @param byteOrder The byte order for multi-byte operations
 * @return A buffer that may or may not be backed by shared memory
 */
expect fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer

// =============================================================================
// Accessing Platform-Specific Native Buffer Objects
// =============================================================================
//
// When you need the actual platform-native buffer object (not just the address),
// cast to the platform-specific implementation class:
//
// **JVM/Android** - Get `java.nio.ByteBuffer`:
// ```kotlin
// val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
// val byteBuffer: ByteBuffer = (buffer as BaseJvmBuffer).byteBuffer
// // Use with NIO channels, memory-mapped files, etc.
// ```
//
// **Apple (iOS/macOS)** - Get `NSMutableData`:
// ```kotlin
// val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
// val nsData: NSMutableData = (buffer as MutableDataBuffer).data
// // Use with Foundation APIs, file I/O, etc.
// ```
//
// **JavaScript** - Get `Int8Array`:
// ```kotlin
// val buffer = PlatformBuffer.allocate(1024)
// val int8Array: Int8Array = (buffer as JsBuffer).buffer
// // Use with WebGL, fetch, WebSockets, etc.
// ```
//
// **WASM** - Get linear memory offset for JS interop:
// ```kotlin
// val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
// val linearBuffer = buffer as LinearBuffer
// val offset = linearBuffer.baseOffset + linearBuffer.position()
// // Create JS DataView: new DataView(wasmExports.memory.buffer, offset, size)
// ```
//
// **Linux** - No native buffer (only ByteArrayBuffer with managed memory):
// ```kotlin
// val buffer = PlatformBuffer.allocate(1024)
// val byteArray: ByteArray = (buffer as ByteArrayBuffer).backingArray
// ```
//
// For pointer-based interop (FFI, JNI, C interop), use [NativeMemoryAccess]:
// ```kotlin
// buffer.nativeMemoryAccess?.let { native ->
//     val address: Long = native.nativeAddress
//     val size: Long = native.nativeSize
// }
// ```
