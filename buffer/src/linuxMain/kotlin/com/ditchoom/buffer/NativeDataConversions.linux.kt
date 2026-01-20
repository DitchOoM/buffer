package com.ditchoom.buffer

/**
 * Returns the native memory address for this buffer's current position.
 *
 * - If the buffer is a [NativeBuffer] or has [NativeMemoryAccess], returns its native address
 * - Otherwise, copies the remaining bytes to a new NativeBuffer and returns its address
 *
 * The returned address can be converted to a CPointer:
 * ```kotlin
 * val ptr = address.toCPointer<ByteVar>()!!
 * ```
 *
 * **Warning**: If a copy is made, the caller is responsible for freeing the allocated memory.
 */
fun ReadBuffer.toNativeData(): Long {
    val native = this.nativeMemoryAccess
    if (native != null) {
        return native.nativeAddress + position()
    }
    val bytes = toByteArray()
    val nativeBuffer = NativeBuffer.allocate(bytes.size, byteOrder)
    nativeBuffer.writeBytes(bytes)
    return nativeBuffer.nativeAddress
}

/**
 * Returns the native memory address for this buffer's current position.
 *
 * - If the buffer is a [NativeBuffer] or has [NativeMemoryAccess], returns its native address
 * - Otherwise, copies the remaining bytes to a new NativeBuffer and returns its address
 *
 * **Warning**: If a copy is made, the caller is responsible for freeing the allocated memory.
 */
fun PlatformBuffer.toMutableNativeData(): Long {
    if (this is NativeBuffer) {
        return nativeAddress + position()
    }
    val bytes = toByteArray()
    val nativeBuffer = NativeBuffer.allocate(bytes.size, byteOrder)
    nativeBuffer.writeBytes(bytes)
    return nativeBuffer.nativeAddress
}
