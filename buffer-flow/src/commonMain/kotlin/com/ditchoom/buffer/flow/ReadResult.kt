package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ReadBuffer
import kotlin.jvm.JvmInline

/**
 * Result of a byte-level read operation.
 *
 * [Data] wraps the buffer containing bytes read. [End] signals clean EOF.
 * [Reset] signals the peer forcibly reset the connection.
 */
sealed interface ReadResult {
    /** Bytes were read successfully. */
    @JvmInline
    value class Data(
        val buffer: ReadBuffer,
    ) : ReadResult

    /** Clean end-of-stream — peer closed gracefully. */
    data object End : ReadResult

    /** Connection reset by peer. */
    data object Reset : ReadResult
}

/** Result of a write operation — number of bytes written. */
@JvmInline
value class BytesWritten(
    val count: Int,
)
