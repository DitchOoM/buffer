@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

import java.nio.ByteBuffer

actual fun allocateNewBuffer(
    size: UInt
): PlatformBuffer = JvmBuffer(ByteBuffer.allocateDirect(size.toInt()))


actual fun String.toBuffer(): PlatformBuffer = JvmBuffer(ByteBuffer.wrap(encodeToByteArray()))

actual fun String.utf8Length(): UInt = encodeToByteArray().size.toUInt()
