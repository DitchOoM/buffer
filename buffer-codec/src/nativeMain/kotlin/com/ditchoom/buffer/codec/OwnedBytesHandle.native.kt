package com.ditchoom.buffer.codec

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.bufferHashCode

actual class OwnedBytesHandle internal constructor(
    internal val buffer: PlatformBuffer,
) {
    override fun equals(other: Any?): Boolean = other is OwnedBytesHandle && this.handleEquals(other)

    override fun hashCode(): Int = this.handleHashCode()
}

actual fun ownedBytesFrom(bytes: PlatformBuffer): OwnedBytesHandle = OwnedBytesHandle(bytes)

actual fun OwnedBytesHandle.asReadBuffer(): ReadBuffer {
    buffer.position(0)
    buffer.setLimit(buffer.capacity)
    return buffer
}

actual fun OwnedBytesHandle.byteSize(): Int = buffer.capacity

actual fun OwnedBytesHandle.handleEquals(other: OwnedBytesHandle): Boolean {
    buffer.position(0)
    buffer.setLimit(buffer.capacity)
    other.buffer.position(0)
    other.buffer.setLimit(other.buffer.capacity)
    return buffer.contentEquals(other.buffer)
}

actual fun OwnedBytesHandle.handleHashCode(): Int {
    buffer.position(0)
    buffer.setLimit(buffer.capacity)
    return bufferHashCode(buffer)
}
