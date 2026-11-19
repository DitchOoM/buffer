package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer

internal actual fun PlatformBuffer.pinByteOrder(order: ByteOrder) = Unit
