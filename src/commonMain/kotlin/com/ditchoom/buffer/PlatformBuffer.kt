@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

interface PlatformBuffer : ReadBuffer, WriteBuffer, SuspendCloseable {
    val capacity: UInt
}


