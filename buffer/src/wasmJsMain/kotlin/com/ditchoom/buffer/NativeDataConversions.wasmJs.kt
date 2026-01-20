package com.ditchoom.buffer

/**
 * Returns the linear memory offset for this buffer's current position.
 *
 * - If the buffer is a [LinearBuffer], returns its offset in linear memory (zero-copy access)
 * - Otherwise, copies the remaining bytes to a new LinearBuffer and returns its offset
 *
 * The returned offset can be used with JavaScript's DataView or Uint8Array:
 * ```javascript
 * const view = new DataView(wasmExports.memory.buffer, offset, size);
 * ```
 *
 * **Note**: If a copy is made, the allocated memory is managed by LinearMemoryAllocator.
 */
fun ReadBuffer.toNativeData(): Int =
    when (this) {
        is LinearBuffer -> baseOffset + position()
        else -> {
            val bytes = toByteArray()
            val (offset, _) = LinearMemoryAllocator.allocate(bytes.size)
            val linearBuffer = LinearBuffer(offset, bytes.size, byteOrder)
            linearBuffer.writeBytes(bytes)
            offset
        }
    }

/**
 * Returns the linear memory offset for this buffer's current position.
 *
 * - If the buffer is a [LinearBuffer], returns its offset in linear memory (zero-copy access)
 * - Otherwise, copies the remaining bytes to a new LinearBuffer and returns its offset
 *
 * **Note**: If a copy is made, the allocated memory is managed by LinearMemoryAllocator.
 */
fun PlatformBuffer.toMutableNativeData(): Int =
    when (this) {
        is LinearBuffer -> baseOffset + position()
        else -> {
            val bytes = toByteArray()
            val (offset, _) = LinearMemoryAllocator.allocate(bytes.size)
            val linearBuffer = LinearBuffer(offset, bytes.size, byteOrder)
            linearBuffer.writeBytes(bytes)
            offset
        }
    }
