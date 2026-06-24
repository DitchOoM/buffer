package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JVM buffer backed by a direct ByteBuffer with native memory access support.
 *
 * This class provides access to the native memory address for use with:
 * - Java NIO channels
 * - JNI/FFI native code
 * - Memory-mapped files
 * - Future Java FFM (Foreign Function & Memory) API
 */
class DirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess {
    init {
        require(byteBuffer.isDirect) { "DirectJvmBuffer requires a direct ByteBuffer" }
    }

    /**
     * The native memory address of the direct ByteBuffer.
     * This address can be used for JNI/FFI interop or with Java's FFM API.
     */
    override val nativeAddress: Long by lazy { getDirectBufferAddress(byteBuffer) }

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Long get() = capacity.toLong()

    // Address of THIS buffer's index 0, position-independent (getUnchecked/getLongUnchecked take ABSOLUTE
    // indices, like get(index)/getLong(index)). Resolved lazily on first unchecked access and cached in a
    // plain field: the value is an idempotent address, so a benign data race only recomputes the same
    // number — no `lazy` volatile in the hot loop. 0 means "unavailable" (a real mapping base is never 0,
    // e.g. JVM<21 without `--add-opens java.base/java.nio`) → the unchecked accessors fall back to the
    // checked path. UNRESOLVED is the not-yet-computed sentinel.
    private var directBaseCache: Long = UNRESOLVED

    private fun directBase(): Long {
        var base = directBaseCache
        if (base == UNRESOLVED) {
            base =
                runCatching {
                    getDirectBufferAddress(
                        byteBuffer.duplicate().apply {
                            position(0)
                            limit(capacity())
                        },
                    )
                }.getOrDefault(0L)
            directBaseCache = base
        }
        return base
    }

    // Unchecked fast paths (see ReadBuffer.getUnchecked): read straight from the native address,
    // bypassing DirectByteBuffer.get() -> ScopedMemoryAccess.checkValidStateRaw (the FFM session-liveness
    // check, ~20% of a direct/mmap scan on JDK 21+). The read goes through directGet*: a global-scope FFM
    // MemorySegment on JDK 21+ (no Unsafe, no --add-opens), sun.misc.Unsafe on 8-20. Safe under the
    // unchecked contract — the caller range-checked the window once and the memory outlives the scan.
    override fun getUnchecked(index: Int): Byte {
        val base = directBase()
        return if (base != 0L) directGetByte(base + index) else get(index)
    }

    override fun getLongUnchecked(index: Int): Long {
        val base = directBase()
        if (base == 0L) return getLong(index)
        val raw = directGetLong(base + index)
        return if (byteOrder == ByteOrder.BIG_ENDIAN) java.lang.Long.reverseBytes(raw) else raw
    }

    // ktlint (no .editorconfig) collapses this expression body onto one line, so it cannot be wrapped.
    @Suppress("MaxLineLength")
    override fun slice(byteOrder: ByteOrder): DirectJvmBuffer = DirectJvmBuffer(byteBuffer.slice().order(byteOrder.toJava()))

    private companion object {
        // Sentinel for "address not yet resolved". Distinct from 0L ("resolved but unavailable").
        private const val UNRESOLVED: Long = Long.MIN_VALUE
    }
}
