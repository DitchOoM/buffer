package com.ditchoom.buffer

import java.nio.ByteBuffer

internal expect fun getDirectBufferAddress(buffer: ByteBuffer): Long
