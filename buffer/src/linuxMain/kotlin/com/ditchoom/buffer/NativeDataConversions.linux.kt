package com.ditchoom.buffer

/**
 * Linux native data wrapper containing a NativeBuffer.
 *
 * Access the underlying NativeBuffer via [nativeBuffer] property.
 * For raw address access, use `nativeBuffer.nativeAddress`.
 */
actual class NativeData(val nativeBuffer: NativeBuffer)

/**
 * Linux mutable native data wrapper containing a NativeBuffer.
 *
 * Access the underlying NativeBuffer via [nativeBuffer] property.
 * For raw address access, use `nativeBuffer.nativeAddress`.
 */
actual class MutableNativeData(val nativeBuffer: NativeBuffer)

/**
 * Converts the remaining bytes of this buffer to a NativeBuffer.
 *
 * **Zero-copy path:**
 * - If the buffer is already a [NativeBuffer], returns a slice sharing the same memory.
 *
 * **Copy path:**
 * - Otherwise, copies the remaining bytes to a new NativeBuffer.
 *
 * For raw address access, use `.nativeBuffer.nativeAddress`.
 *
 * **Memory management**: If a copy is made, the returned buffer owns its memory
 * and should be closed when no longer needed.
 */
actual fun ReadBuffer.toNativeData(): NativeData {
    if (this is NativeBuffer) {
        // Zero-copy: return slice of current buffer
        val slice = this.slice()
        return NativeData(slice as NativeBuffer)
    }
    // Copy to new NativeBuffer
    val bytes = toByteArray()
    val nativeBuffer = NativeBuffer.allocate(bytes.size, byteOrder)
    nativeBuffer.writeBytes(bytes)
    nativeBuffer.resetForRead()
    return NativeData(nativeBuffer)
}

/**
 * Converts the remaining bytes of this buffer to a mutable NativeBuffer.
 *
 * **Zero-copy path:**
 * - If the buffer is already a [NativeBuffer], returns a duplicate sharing the same memory.
 *
 * **Copy path:**
 * - Otherwise, copies the remaining bytes to a new NativeBuffer.
 *
 * For raw address access, use `.nativeBuffer.nativeAddress`.
 *
 * **Memory management**: If a copy is made, the returned buffer owns its memory
 * and should be closed when no longer needed.
 */
actual fun PlatformBuffer.toMutableNativeData(): MutableNativeData {
    if (this is NativeBuffer) {
        // Zero-copy: return duplicate of current buffer
        val duplicate = this.duplicate()
        return MutableNativeData(duplicate as NativeBuffer)
    }
    // Copy to new NativeBuffer
    val bytes = toByteArray()
    val nativeBuffer = NativeBuffer.allocate(bytes.size, byteOrder)
    nativeBuffer.writeBytes(bytes)
    nativeBuffer.resetForRead()
    return MutableNativeData(nativeBuffer)
}
