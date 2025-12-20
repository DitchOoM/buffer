package com.ditchoom.buffer

actual inline fun <R> withUnsafeBuffer(
    size: Int,
    byteOrder: ByteOrder,
    block: (UnsafeBuffer) -> R,
): R = DefaultUnsafeBuffer.withBuffer(size, byteOrder, block)
