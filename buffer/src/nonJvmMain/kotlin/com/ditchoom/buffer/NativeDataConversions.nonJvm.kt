package com.ditchoom.buffer

/**
 * Converts this buffer to a ByteArray.
 *
 * - If the buffer has [ManagedMemoryAccess] and the full remaining content matches the array,
 *   returns the backing array (zero-copy)
 * - Otherwise, copies the remaining bytes to a new ByteArray
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
    }
    return readByteArray(remaining())
}
