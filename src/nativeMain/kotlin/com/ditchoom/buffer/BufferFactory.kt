@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer


actual fun allocateNewBuffer(
    size: UInt
): PlatformBuffer = NativeBuffer(ByteArray(size.toInt()))

actual fun String.toBuffer(): PlatformBuffer = NativeBuffer(this.encodeToByteArray())
actual fun String.utf8Length(): UInt = encodeToByteArray().size.toUInt()
