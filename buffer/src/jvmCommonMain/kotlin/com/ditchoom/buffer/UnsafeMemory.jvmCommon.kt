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

    // DirectByteBuffer reflective wrap path. The constructor lookup runs once per
    // process under the lazy's default SYNCHRONIZED mode. We capture both the
    // resolved Constructor on success and the underlying Throwable on failure so
    // callers can attach the real cause to the exception they raise — instead of
    // swallowing it behind a generic "not available" message.
    private sealed class DirectByteBufferAccess {
        data class Available(
            val constructor: java.lang.reflect.Constructor<*>,
        ) : DirectByteBufferAccess()

        data class Unavailable(
            val cause: Throwable,
        ) : DirectByteBufferAccess()
    }

    private val directByteBufferAccess: DirectByteBufferAccess by lazy {
        try {
            val clazz = Class.forName("java.nio.DirectByteBuffer")
            val constructor =
                clazz.getDeclaredConstructor(
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            constructor.isAccessible = true
            DirectByteBufferAccess.Available(constructor)
        } catch (e: Throwable) {
            DirectByteBufferAccess.Unavailable(e)
        }
    }

    /**
     * Creates a DirectByteBuffer wrapping the given native memory via reflection.
     *
     * On Android API 28+ this can fail because `java.nio.DirectByteBuffer` is a
     * non-SDK class subject to hidden-API enforcement on non-debuggable test
     * APKs. The underlying [Throwable] (NoSuchMethodException,
     * IllegalAccessException, or whatever the runtime emitted) is chained as
     * the `cause` of the thrown [UnsupportedOperationException] so callers can
     * see the real reason.
     *
     * Callers wanting a non-reflective Android path should prefer JNI's
     * `NewDirectByteBuffer` instead — that's a public, supported entry point.
     */
    fun wrapAsDirectByteBuffer(
        address: Long,
        capacity: Int,
    ): java.nio.ByteBuffer =
        when (val state = directByteBufferAccess) {
            is DirectByteBufferAccess.Available ->
                try {
                    state.constructor.newInstance(address, capacity) as java.nio.ByteBuffer
                } catch (e: Throwable) {
                    throw UnsupportedOperationException(
                        "Reflective DirectByteBuffer constructor invocation failed " +
                            "(address=$address, capacity=$capacity): ${e.message ?: e::class.qualifiedName}",
                        e,
                    )
                }
            is DirectByteBufferAccess.Unavailable ->
                throw UnsupportedOperationException(
                    "Reflective DirectByteBuffer constructor is not accessible on this runtime " +
                        "(java.nio.DirectByteBuffer(long, int) lookup failed). On Android API 28+, " +
                        "non-SDK classes are gated by hidden-API enforcement on non-debuggable test " +
                        "APKs; use JNI's NewDirectByteBuffer instead. Cause: " +
                        "${state.cause.message ?: state.cause::class.qualifiedName}",
                    state.cause,
                )
        }
}
