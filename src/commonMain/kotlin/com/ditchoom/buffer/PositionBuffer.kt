package com.ditchoom.buffer

interface PositionBuffer {
    fun setLimit(limit: Int)
    fun limit(): Int
    fun position(): Int
    fun position(newPosition: Int)
    fun remaining() = limit() - position()
    fun hasRemaining() = position() < limit()
}
