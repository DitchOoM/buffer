/**
 * JavaScript memory manager for Kotlin/WASM buffer allocation.
 *
 * This module manages persistent allocations in WASM's linear memory,
 * allowing both WASM (via Pointer) and JS (via DataView) to access
 * the same underlying memory without copying.
 */

let wasmMemory = null;
let heapBase = 0;
let nextOffset = 0;

/**
 * Initialize the buffer memory manager with the WASM module's memory.
 * @param {WebAssembly.Memory} memory - The WASM module's exported memory
 * @param {number} reservedBytes - Number of bytes reserved for Kotlin runtime
 */
export function initBufferMemory(memory, reservedBytes) {
    wasmMemory = memory;
    heapBase = reservedBytes;
    nextOffset = heapBase;
}

/**
 * Allocate a buffer in WASM linear memory.
 * @param {number} requestedSize - Requested size in bytes
 * @returns {{ptr: number, capacity: number}} - Pointer and actual allocated capacity
 */
export function allocBuffer(requestedSize) {
    // 8-byte alignment for optimal memory access
    const aligned = (requestedSize + 7) & ~7;
    const ptr = nextOffset;
    nextOffset += aligned;

    // Grow memory if needed
    const requiredPages = Math.ceil(nextOffset / 65536);
    const currentPages = wasmMemory.buffer.byteLength / 65536;
    if (requiredPages > currentPages) {
        const pagesToGrow = requiredPages - currentPages;
        wasmMemory.grow(pagesToGrow);
    }

    return { ptr, capacity: aligned };
}

/**
 * Get the underlying ArrayBuffer for JS access.
 * Note: This buffer reference may become invalid after memory.grow(),
 * so always call this fresh when needed.
 * @returns {ArrayBuffer} - The WASM memory's ArrayBuffer
 */
export function getMemoryBuffer() {
    return wasmMemory.buffer;
}

/**
 * Get a DataView for a specific buffer region.
 * @param {number} ptr - Start offset in memory
 * @param {number} length - Length of the region
 * @returns {DataView} - DataView for the memory region
 */
export function getDataView(ptr, length) {
    return new DataView(wasmMemory.buffer, ptr, length);
}

/**
 * Get an Int8Array view for a specific buffer region.
 * @param {number} ptr - Start offset in memory
 * @param {number} length - Length of the region
 * @returns {Int8Array} - Int8Array view for the memory region
 */
export function getInt8Array(ptr, length) {
    return new Int8Array(wasmMemory.buffer, ptr, length);
}

/**
 * Check if the memory manager has been initialized.
 * @returns {boolean} - True if initialized
 */
export function isInitialized() {
    return wasmMemory !== null;
}

/**
 * Get current allocation statistics.
 * @returns {{heapBase: number, nextOffset: number, totalAllocated: number}}
 */
export function getAllocationStats() {
    return {
        heapBase,
        nextOffset,
        totalAllocated: nextOffset - heapBase,
    };
}
