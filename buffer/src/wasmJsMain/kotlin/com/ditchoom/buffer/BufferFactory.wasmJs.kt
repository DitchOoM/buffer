package com.ditchoom.buffer

/**
 * WASM buffer allocation using native linear memory.
 *
 * - **Heap**: Uses ByteArrayBuffer (WasmGC heap) - good for temporary buffers
 * - **Direct/SharedMemory**: Uses LinearBuffer (WASM linear memory) - enables JS interop
 *
 * LinearBuffer provides:
 * - Native WASM i32.load/i32.store instructions for read/write
 * - Zero-copy slicing via pointer arithmetic
 * - Same memory accessible from JavaScript via DataView on wasmExports.memory.buffer
 *
 * **Warning**: Due to a Kotlin/WASM optimizer issue, high-frequency allocation of
 * Direct buffers in production builds may cause stack overflow. If you're allocating
 * many buffers in a tight loop, consider using AllocationZone.Heap or object pooling.
 */
actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer =
    when (zone) {
        AllocationZone.Heap -> {
            // Heap uses ByteArrayBuffer (WasmGC heap)
            ByteArrayBuffer(ByteArray(size), byteOrder)
        }
        AllocationZone.Direct, AllocationZone.SharedMemory -> {
            // Direct/SharedMemory use LinearBuffer (WASM linear memory)
            // Enables JS interop via shared memory
            val (offset, _) = LinearMemoryAllocator.allocate(size)
            LinearBuffer(offset, size, byteOrder)
        }
        is AllocationZone.Custom -> zone.allocator(size)
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

/**
 * Allocates a buffer with guaranteed native memory access (LinearBuffer).
 * This is equivalent to allocate with Direct zone but makes the intent explicit.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    val (offset, _) = LinearMemoryAllocator.allocate(size)
    return LinearBuffer(offset, size, byteOrder)
}
