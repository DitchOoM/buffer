package com.ditchoom.buffer

/**
 * Converts this buffer to a ByteArray.
 *
 * - If the buffer is backed by a ByteArray (ManagedMemoryAccess) and the full array matches
 *   the remaining content, returns the backing array (zero-copy)
 * - Otherwise, copies the remaining bytes to a new ByteArray
 *
 * @return ByteArray containing the buffer's remaining content
 */
expect fun ReadBuffer.toByteArray(): ByteArray
