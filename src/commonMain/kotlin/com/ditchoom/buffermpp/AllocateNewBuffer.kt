@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffermpp

expect fun allocateNewBuffer(size: UInt, limits: BufferMemoryLimit = DefaultMemoryLimit): PlatformBuffer

expect fun String.toBuffer(): PlatformBuffer
expect fun String.utf8Length(): UInt
