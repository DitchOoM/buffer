@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

expect fun allocateNewBuffer(size: UInt, byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN): ParcelablePlatformBuffer
expect fun String.toBuffer(): ParcelablePlatformBuffer
expect fun String.utf8Length(): UInt
