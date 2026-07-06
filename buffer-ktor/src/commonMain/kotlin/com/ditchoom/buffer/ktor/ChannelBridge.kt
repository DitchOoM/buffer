package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.kotlinxio.copyToKotlinxIoBuffer
import com.ditchoom.buffer.kotlinxio.copyToPlatformBuffer
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeByteArray
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Reads **all** remaining bytes from this [ByteReadChannel] into a new [PlatformBuffer] allocated
 * by [factory], positioned for reading (`position = 0`, `limit = size`).
 *
 * Copy semantics: bytes are copied out of the channel into a fresh buffer the caller owns. The
 * returned buffer is independent of the channel and safe to retain.
 *
 * @param factory allocator for the destination buffer (default [BufferFactory.Default]).
 */
public suspend fun ByteReadChannel.readRemainingBuffer(factory: BufferFactory = BufferFactory.Default): PlatformBuffer {
    val staging = Buffer()
    readRemaining().use { source ->
        source.transferTo(staging)
    }
    return staging.copyToPlatformBuffer(factory)
}

/**
 * Writes [buffer]'s remaining bytes (`position` until `limit`) to this [ByteWriteChannel] and
 * flushes. **Does not change [buffer]'s position** — the write is taken from a non-destructive
 * snapshot, so the buffer remains readable afterwards.
 *
 * Copy semantics: bytes are copied out of [buffer] into the channel.
 */
public suspend fun ByteWriteChannel.writeBuffer(buffer: ReadBuffer) {
    val snapshot = buffer.copyToKotlinxIoBuffer()
    writeByteArray(snapshot.readByteArray())
    flush()
}
