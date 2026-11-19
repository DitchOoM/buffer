package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.toJava

internal actual fun PlatformBuffer.pinByteOrder(order: ByteOrder) {
    if (this is BaseJvmBuffer) byteBuffer.order(order.toJava())
}
