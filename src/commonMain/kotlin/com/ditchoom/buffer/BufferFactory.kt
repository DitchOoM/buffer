@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

expect fun allocateNewBuffer(size: UInt): ParcelablePlatformBuffer
expect fun String.toBuffer(): ParcelablePlatformBuffer
expect fun String.utf8Length(): UInt
