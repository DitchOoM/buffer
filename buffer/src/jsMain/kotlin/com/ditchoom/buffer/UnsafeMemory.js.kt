package com.ditchoom.buffer

actual object UnsafeMemory {
    actual val isSupported: Boolean = false

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException(
            "UnsafeMemory is not supported on JavaScript. " +
                "JavaScript does not provide direct memory access.",
        )

    actual fun getByte(address: Long): Byte = unsupported()

    actual fun putByte(
        address: Long,
        value: Byte,
    ): Unit = unsupported()

    actual fun getShort(address: Long): Short = unsupported()

    actual fun putShort(
        address: Long,
        value: Short,
    ): Unit = unsupported()

    actual fun getInt(address: Long): Int = unsupported()

    actual fun putInt(
        address: Long,
        value: Int,
    ): Unit = unsupported()

    actual fun getLong(address: Long): Long = unsupported()

    actual fun putLong(
        address: Long,
        value: Long,
    ): Unit = unsupported()

    actual fun copyMemory(
        srcAddress: Long,
        dstAddress: Long,
        size: Long,
    ): Unit = unsupported()

    actual fun setMemory(
        address: Long,
        size: Long,
        value: Byte,
    ): Unit = unsupported()

    actual fun copyMemoryToArray(
        srcAddress: Long,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ): Unit = unsupported()

    actual fun copyMemoryFromArray(
        src: ByteArray,
        srcOffset: Int,
        dstAddress: Long,
        length: Int,
    ): Unit = unsupported()
}
