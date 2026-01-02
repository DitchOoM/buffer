package com.ditchoom.buffer

interface PlatformBuffer :
    ReadWriteBuffer,
    SuspendCloseable,
    Parcelable {
    companion object
}
