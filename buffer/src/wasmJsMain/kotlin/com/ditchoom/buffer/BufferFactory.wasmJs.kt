package com.ditchoom.buffer

/**
 * WASM buffer allocation using native linear memory.
 *
 * This implementation uses LinearBuffer which provides:
 * - Native WASM i32.load/i32.store instructions for read/write
 * - Zero-copy slicing via pointer arithmetic
 * - Same memory accessible from JavaScript via DataView
 */
actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }

    // Allocate in linear memory (allocator returns aligned capacity, but we use requested size)
    val (offset, _) = LinearMemoryAllocator.allocate(size)
    return LinearBuffer(offset, size, byteOrder)
}

/**
 * Wrap a ByteArray in a buffer.
 *
 * This uses ByteArrayBuffer (not LinearBuffer) because ByteArray lives in the
 * WasmGC heap, which is separate from WASM linear memory. There's no way to
 * get a Pointer to a ByteArray's data.
 *
 * ByteArrayBuffer shares memory with the original array, so modifications
 * to the original ByteArray are visible in the buffer (and vice versa).
 */
actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = ByteArrayBuffer(array, byteOrder)
