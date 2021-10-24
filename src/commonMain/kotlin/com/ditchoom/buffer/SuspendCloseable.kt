package com.ditchoom.buffer

interface SuspendCloseable {
    suspend fun close()
}