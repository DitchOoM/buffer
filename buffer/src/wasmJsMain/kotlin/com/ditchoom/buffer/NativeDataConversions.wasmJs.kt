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
actual fun ReadBuffer.toNativeData(): NativeData =
    NativeData(
        when (this) {
            is LinearBuffer -> this.slice() as LinearBuffer
            else -> {
                val bytes = toByteArray()
                val (offset, _) = LinearMemoryAllocator.allocate(bytes.size)
                val linearBuffer = LinearBuffer(offset, bytes.size, byteOrder)
                linearBuffer.writeBytes(bytes)
                linearBuffer.resetForRead()
                linearBuffer
            }
        },
    )

/**
 * Converts the remaining bytes of this buffer to a mutable LinearBuffer.
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
actual fun PlatformBuffer.toMutableNativeData(): MutableNativeData =
    MutableNativeData(
        when (this) {
            is LinearBuffer -> {
                // Create a new LinearBuffer view sharing the same memory
                val newBuffer = LinearBuffer(baseOffset + position(), remaining(), byteOrder)
                newBuffer
            }
            else -> {
                val bytes = toByteArray()
                val (offset, _) = LinearMemoryAllocator.allocate(bytes.size)
                val linearBuffer = LinearBuffer(offset, bytes.size, byteOrder)
                linearBuffer.writeBytes(bytes)
                linearBuffer.resetForRead()
                linearBuffer
            }
        },
    )
