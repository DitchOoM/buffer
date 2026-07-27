package com.ditchoom.buffer

/**
 * WASM native data wrapper containing a LinearBuffer.
 *
 * Access the underlying LinearBuffer via [linearBuffer] property.
 * For raw offset access, use `linearBuffer.baseOffset`.
 */
actual class NativeData(
    val linearBuffer: LinearBuffer,
)

/**
 * WASM mutable native data wrapper containing a LinearBuffer.
 *
 * Access the underlying LinearBuffer via [linearBuffer] property.
 * For raw offset access, use `linearBuffer.baseOffset`.
 */
actual class MutableNativeData(
    val linearBuffer: LinearBuffer,
)

/**
 * Converts the remaining bytes of this buffer to a LinearBuffer.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * **Zero-copy path:**
 * - If the buffer is already a [LinearBuffer], returns a slice sharing the same memory.
 *
 * **Copy path:**
 * - Otherwise, copies the remaining bytes to a new LinearBuffer.
 *
 * For raw offset access, use `.linearBuffer.baseOffset`.
 *
 * **Memory management**: If a copy is made, the allocated memory is managed
 * by LinearMemoryAllocator.
 */
actual fun ReadBuffer.toNativeData(): NativeData {
    val unwrapped = unwrapFully()
    if (unwrapped !== this) return unwrapped.toNativeData()
    return NativeData(
        when (this) {
            is LinearBuffer -> this.slice() as LinearBuffer
            else -> {
                val bytes = toByteArray()
                val (offset, _) = LinearMemoryAllocator.allocate(bytes.size)
                // Freshly allocated, so the returned wrapper owns it: releasing the LinearBuffer
                // returns the copy to the allocator.
                val linearBuffer = LinearBuffer(offset, bytes.size, byteOrder, owned = true)
                linearBuffer.writeBytes(bytes)
                linearBuffer.resetForRead()
                linearBuffer
            }
        },
    )
}

/**
 * Converts the remaining bytes of this buffer to a mutable LinearBuffer.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * **Zero-copy path:**
 * - If the buffer is already a [LinearBuffer], returns a duplicate sharing the same memory.
 *
 * **Copy path:**
 * - Otherwise, copies the remaining bytes to a new LinearBuffer.
 *
 * For raw offset access, use `.linearBuffer.baseOffset`.
 *
 * **Memory management**: If a copy is made, the allocated memory is managed
 * by LinearMemoryAllocator.
 */
actual fun PlatformBuffer.toMutableNativeData(): MutableNativeData {
    val unwrapped = unwrapFully()
    return MutableNativeData(
        when (unwrapped) {
            is LinearBuffer -> {
                // Create a new non-owning LinearBuffer view sharing the same memory
                LinearBuffer(
                    unwrapped.baseOffset + unwrapped.position(),
                    unwrapped.remaining(),
                    unwrapped.byteOrder,
                    owned = false,
                )
            }
            else -> {
                val bytes = toByteArray()
                val (offset, _) = LinearMemoryAllocator.allocate(bytes.size)
                // Freshly allocated, so the returned wrapper owns it: releasing the LinearBuffer
                // returns the copy to the allocator.
                val linearBuffer = LinearBuffer(offset, bytes.size, byteOrder, owned = true)
                linearBuffer.writeBytes(bytes)
                linearBuffer.resetForRead()
                linearBuffer
            }
        },
    )
}
