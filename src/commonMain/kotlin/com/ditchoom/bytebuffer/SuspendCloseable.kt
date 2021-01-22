package com.ditchoom.bytebuffer

interface SuspendCloseable {
    suspend fun close()
}