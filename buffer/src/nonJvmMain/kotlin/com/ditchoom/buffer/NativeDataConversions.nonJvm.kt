package com.ditchoom.buffer

/**
 * Converts the remaining bytes of this buffer to a ByteArray.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * **Zero-copy path:**
 * - If the buffer has [ManagedMemoryAccess] and the full remaining content matches the array,
 *   returns the backing array.
 *
 * **Copy path:**
 * - Otherwise, copies the remaining bytes to a new ByteArray.
 */
actual fun ReadBuffer.toByteArray(): ByteArray {
    val managed = this.managedMemoryAccess
    if (managed != null) {
        val array = managed.backingArray
        val offset = managed.arrayOffset
        val pos = position()
        val rem = remaining()
        if (offset == 0 && pos == 0 && rem == array.size) {
            return array
        }
        // Copy without modifying position
        return array.copyOfRange(offset + pos, offset + pos + rem)
    }
    // Fallback: read and restore position
    val pos = position()
    val result = readByteArray(remaining())
    position(pos)
    return result
}
