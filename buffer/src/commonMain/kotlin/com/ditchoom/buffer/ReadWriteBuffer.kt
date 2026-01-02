package com.ditchoom.buffer

/**
 * A buffer that supports both read and write operations.
 *
 * This interface combines [ReadBuffer] and [WriteBuffer] capabilities,
 * providing a common type for buffers that can be both written to and read from.
 *
 * Both [PlatformBuffer] and [com.ditchoom.buffer.pool.PooledBuffer] implement
 * this interface, making it useful for APIs that need to allocate and return
 * buffers without caring about the specific buffer type.
 */
interface ReadWriteBuffer :
    ReadBuffer,
    WriteBuffer {
    /**
     * The total capacity of this buffer in bytes.
     */
    val capacity: Int
}
