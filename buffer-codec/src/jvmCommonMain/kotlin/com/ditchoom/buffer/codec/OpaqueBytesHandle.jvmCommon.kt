package com.ditchoom.buffer.codec

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.bufferHashCode

/**
 * JVM + Android actual for [OpaqueBytesHandle]. See common kdoc for design
 * rationale; both jvm and androidTarget source-sets dependsOn `jvmCommonMain`
 * so this single file serves both.
 */
actual class OpaqueBytesHandle internal constructor(
    internal val buffer: PlatformBuffer,
)

actual fun opaqueBytesFrom(bytes: PlatformBuffer): OpaqueBytesHandle = OpaqueBytesHandle(bytes)

actual fun OpaqueBytesHandle.asReadBuffer(): ReadBuffer {
    buffer.position(0)
    buffer.setLimit(buffer.capacity)
    return buffer
}

actual fun OpaqueBytesHandle.byteSize(): Int = buffer.capacity

actual fun OpaqueBytesHandle.handleEquals(other: OpaqueBytesHandle): Boolean {
    buffer.position(0)
    buffer.setLimit(buffer.capacity)
    other.buffer.position(0)
    other.buffer.setLimit(other.buffer.capacity)
    return buffer.contentEquals(other.buffer)
}

actual fun OpaqueBytesHandle.handleHashCode(): Int {
    buffer.position(0)
    buffer.setLimit(buffer.capacity)
    return bufferHashCode(buffer)
}
