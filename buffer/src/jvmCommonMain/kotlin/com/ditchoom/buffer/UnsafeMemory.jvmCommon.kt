@file:Suppress("UNCHECKED_CAST")

package com.ditchoom.buffer

import sun.misc.Unsafe

actual object UnsafeMemory {
    private val unsafe: Unsafe? =
        try {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        } catch (e: Exception) {
            null
        }

    actual val isSupported: Boolean = unsafe != null

    private fun checkSupported() {
        if (unsafe == null) {
            throw UnsupportedOperationException(
                "UnsafeMemory is not supported on this platform. sun.misc.Unsafe is not available.",
            )
        }
    }

    actual fun getByte(address: Long): Byte {
        checkSupported()
        return unsafe!!.getByte(address)
    }

    actual fun putByte(
        address: Long,
        value: Byte,
    ) {
        checkSupported()
        unsafe!!.putByte(address, value)
    }

    actual fun getShort(address: Long): Short {
        checkSupported()
        return unsafe!!.getShort(address)
    }

    actual fun putShort(
        address: Long,
        value: Short,
    ) {
        checkSupported()
        unsafe!!.putShort(address, value)
    }

    actual fun getInt(address: Long): Int {
        checkSupported()
        return unsafe!!.getInt(address)
    }

    actual fun putInt(
        address: Long,
        value: Int,
    ) {
        checkSupported()
        unsafe!!.putInt(address, value)
    }

    actual fun getLong(address: Long): Long {
        checkSupported()
        return unsafe!!.getLong(address)
    }

    actual fun putLong(
        address: Long,
        value: Long,
    ) {
        checkSupported()
        unsafe!!.putLong(address, value)
    }

    actual fun copyMemory(
        srcAddress: Long,
        dstAddress: Long,
        size: Long,
    ) {
        checkSupported()
        unsafe!!.copyMemory(srcAddress, dstAddress, size)
    }

    actual fun setMemory(
        address: Long,
        size: Long,
        value: Byte,
    ) {
        checkSupported()
        unsafe!!.setMemory(address, size, value)
    }

    // Array offset for byte arrays - required for Unsafe.copyMemory with arrays
    private val BYTE_ARRAY_BASE_OFFSET: Long by lazy {
        unsafe!!.arrayBaseOffset(ByteArray::class.java).toLong()
    }

    actual fun copyMemoryToArray(
        srcAddress: Long,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        checkSupported()
        unsafe!!.copyMemory(
            null,
            srcAddress,
            dest,
            BYTE_ARRAY_BASE_OFFSET + destOffset,
            length.toLong(),
        )
    }

    actual fun copyMemoryFromArray(
        src: ByteArray,
        srcOffset: Int,
        dstAddress: Long,
        length: Int,
    ) {
        checkSupported()
        unsafe!!.copyMemory(
            src,
            BYTE_ARRAY_BASE_OFFSET + srcOffset,
            null,
            dstAddress,
            length.toLong(),
        )
    }

    // DirectByteBuffer wrapper - tested once per process, falls back gracefully
    // Uses default SYNCHRONIZED mode since this singleton could be accessed from multiple threads
    private val directByteBufferConstructor: java.lang.reflect.Constructor<*>? by lazy {
        try {
            val clazz = Class.forName("java.nio.DirectByteBuffer")
            val constructor =
                clazz.getDeclaredConstructor(
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            constructor.isAccessible = true
            constructor
        } catch (e: Exception) {
            null // Reflection not available on this platform
        }
    }

    /**
     * Attempts to create a DirectByteBuffer wrapping the given native memory.
     * Uses reflection (tested once per process). Returns null if not supported.
     */
    fun tryWrapAsDirectByteBuffer(
        address: Long,
        capacity: Int,
    ): java.nio.ByteBuffer? {
        val constructor = directByteBufferConstructor ?: return null
        return try {
            constructor.newInstance(address, capacity) as java.nio.ByteBuffer
        } catch (e: Exception) {
            null
        }
    }
}
