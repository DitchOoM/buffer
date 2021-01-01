package com.ditchoom.buffermpp

interface SuspendCloseable {
    suspend fun close()
}