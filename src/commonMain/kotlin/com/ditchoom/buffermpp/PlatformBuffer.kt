@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffermpp

interface PlatformBuffer : ReadBuffer, WriteBuffer, SuspendCloseable {
    val type: BufferType
    val capacity: UInt
    fun put(buffer: PlatformBuffer)
}

