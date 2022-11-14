package com.ditchoom.buffer

interface PlatformBuffer : ReadBuffer, WriteBuffer, SuspendCloseable, Parcelable {
    val capacity: Int
    val byteOrder: ByteOrder

    companion object
}
