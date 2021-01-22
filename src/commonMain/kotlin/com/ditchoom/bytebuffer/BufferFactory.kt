@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.bytebuffer

expect fun allocateNewBuffer(size: UInt): PlatformBuffer
expect fun String.toBuffer(): PlatformBuffer
expect fun String.utf8Length(): UInt
