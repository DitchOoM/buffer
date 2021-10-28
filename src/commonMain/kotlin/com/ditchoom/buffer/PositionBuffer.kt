package com.ditchoom.buffer

interface PositionBuffer {
    fun setLimit(limit: Int)
    fun limit(): UInt
    fun position(): UInt
    fun position(newPosition: Int)
    fun remaining() = limit() - position()
    fun hasRemaining() = position() < limit()
}