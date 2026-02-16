package com.ditchoom.buffer.stream

/**
 * Thrown when a read operation on an auto-filling stream processor cannot be satisfied
 * because the underlying data source is exhausted (e.g., socket closed, EOF reached).
 *
 * Protocol implementations catch this to detect clean disconnections:
 * ```kotlin
 * try {
 *     val frame = frameReader.readFrame()
 * } catch (e: EndOfStreamException) {
 *     // Remote closed the connection
 *     transitionToDisconnected()
 * }
 * ```
 */
class EndOfStreamException(
    message: String = "End of stream",
    cause: Throwable? = null,
) : Exception(message, cause)
