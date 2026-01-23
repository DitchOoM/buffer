package com.ditchoom.buffer

/**
 * Linux native data wrapper containing a NativeBuffer.
 *
 * Access the underlying NativeBuffer via [nativeBuffer] property.
 * For raw address access, use `nativeBuffer.nativeAddress`.
 */
actual class NativeData(
    val nativeBuffer: NativeBuffer,
)

/**
 * Linux mutable native data wrapper containing a NativeBuffer.
 *
 * Access the underlying NativeBuffer via [nativeBuffer] property.
 * For raw address access, use `nativeBuffer.nativeAddress`.
 */
actual class MutableNativeData(
    val nativeBuffer: NativeBuffer,
)

/**
 * Converts the remaining bytes of this buffer to a NativeBuffer.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * Always copies the remaining bytes to a new NativeBuffer to ensure
 * the returned buffer has full NativeBuffer capabilities.
 *
 * For raw address access, use `.nativeBuffer.nativeAddress`.
 *
 * **Memory management**: The returned buffer owns its memory
 * and should be closed when no longer needed.
 */
actual fun ReadBuffer.toNativeData(): NativeData {
    val bytes = toByteArray()
    val nativeBuffer = NativeBuffer.allocate(bytes.size, byteOrder)
    nativeBuffer.writeBytes(bytes)
    nativeBuffer.resetForRead()
    return NativeData(nativeBuffer)
}

/**
 * Converts the remaining bytes of this buffer to a mutable NativeBuffer.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * Always copies the remaining bytes to a new NativeBuffer to ensure
 * the returned buffer has full NativeBuffer capabilities.
 *
 * For raw address access, use `.nativeBuffer.nativeAddress`.
 *
 * **Memory management**: The returned buffer owns its memory
 * and should be closed when no longer needed.
 */
actual fun PlatformBuffer.toMutableNativeData(): MutableNativeData {
    val bytes = toByteArray()
    val nativeBuffer = NativeBuffer.allocate(bytes.size, byteOrder)
    nativeBuffer.writeBytes(bytes)
    nativeBuffer.resetForRead()
    return MutableNativeData(nativeBuffer)
}
