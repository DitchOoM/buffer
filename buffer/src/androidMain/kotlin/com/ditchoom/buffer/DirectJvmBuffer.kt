package com.ditchoom.buffer

import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Android buffer backed by a direct ByteBuffer with both native and managed memory access.
 *
 * On Android, [ByteBuffer.allocateDirect] allocates a non-movable byte array via
 * [dalvik.system.VMRuntime.newNonMovableArray][https://cs.android.com/android/platform/superproject/+/android-16.0.0_r2:libcore/ojluni/src/main/java/java/nio/DirectByteBuffer.java;l=73].
 * The native address and the backing `byte[]` point to the **same memory**, so this class
 * implements both [NativeMemoryAccess] and [ManagedMemoryAccess] — zero-copy in either
 * direction.
 *
 * This differs from JVM where direct buffers use off-heap memory (`Unsafe.allocateMemory`)
 * with no backing array. On Android, "direct" means "pinned/non-movable" rather than
 * "off-heap," which is why APIs like JNI, Camera2, and MediaCodec require direct buffers:
 * the GC guarantees it will never relocate the underlying array, so native code can safely
 * hold a raw pointer.
 *
 * On Android 33+, uses MethodHandle for faster address lookup.
 */
class DirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : JvmBuffer(byteBuffer),
    NativeMemoryAccess,
    ManagedMemoryAccess {
    init {
        require(byteBuffer.isDirect) { "DirectJvmBuffer requires a direct ByteBuffer" }
    }

    /**
     * The native memory address of the direct ByteBuffer.
     * This address can be used for JNI/FFI interop.
     */
    override val nativeAddress: Long by lazy { getDirectBufferAddress(byteBuffer) }

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Long get() = capacity.toLong()

    /**
     * The backing non-movable byte array. On Android, this is the same memory as [nativeAddress].
     */
    override val backingArray: ByteArray get() = byteBuffer.array()

    /**
     * The offset within [backingArray] where buffer data begins (alignment padding).
     */
    override val arrayOffset: Int get() = (byteBuffer as Buffer).arrayOffset()

    override fun slice() = DirectJvmBuffer(byteBuffer.slice())
}
