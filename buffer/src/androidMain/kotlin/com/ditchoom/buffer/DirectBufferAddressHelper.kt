package com.ditchoom.buffer

import java.nio.ByteBuffer

internal actual fun getDirectBufferAddress(buffer: ByteBuffer): Long = AndroidBufferHelper.getDirectBufferAddress(buffer)
