package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer

/**
 * A [PlatformBuffer] decorator that zeroes its full backing storage when it is freed,
 * so cryptographic key material does not linger on the heap after use.
 *
 * The constructor is `internal`: a [SecureBuffer] can only originate from
 * [SecureBufferFactory] (via [BufferFactory.secure]). This guarantees that anything typed
 * "secure" really does wipe — there is no way to hand-roll an unwiped one — and that the
 * full-capacity wipe (not just `remaining()`) is performed in exactly one audited place.
 *
 * The wipe runs on every standard teardown path, because they all funnel through
 * [freeNativeMemory]: `use {}`, `freeIfNeeded()`, `freeAll()`, and an explicit
 * `freeNativeMemory()` call. There is **no GC finalizer hook** — a buffer that is never
 * explicitly freed is never wiped — so secret-bearing buffers should come from a
 * deterministic factory and be released with `use {}`:
 *
 * ```kotlin
 * BufferFactory.deterministic().secure().allocate(32).use { key ->
 *     // ... key is zeroed here, at end of block ...
 * }
 * ```
 *
 * **Best-effort.** On GC-managed backings (heap [ByteArray], JS `Int8Array`) the runtime
 * may already have copied the bytes elsewhere before the wipe; the guarantee is strongest
 * on native/direct backings (direct `ByteBuffer`, FFM, malloc, WASM linear memory). This
 * is defense-in-depth, not a defense against a live-memory adversary.
 */
class SecureBuffer internal constructor(
    private val inner: PlatformBuffer,
) : PlatformBuffer by inner {
    private var wiped = false

    override fun freeNativeMemory() {
        if (!wiped) {
            wiped = true
            try {
                // Address the WHOLE buffer, not just remaining() — fill() writes
                // position..limit, so widen the window to cover every byte first.
                inner.position(0)
                inner.setLimit(inner.capacity)
                inner.fill(0)
            } catch (_: Throwable) {
                // A best-effort wipe must never mask the real free below; swallow and free.
            }
        }
        inner.freeNativeMemory()
    }

    override fun slice(byteOrder: ByteOrder): PlatformBuffer = inner.slice(byteOrder)
}

/**
 * A [BufferFactory] decorator whose buffers wipe their backing storage on free. Composes
 * over any delegate factory — `BufferFactory.deterministic().secure()`,
 * `someFactory.withPooling(pool).secure()`, etc. — so the secure-erase behavior layers
 * on top of whatever allocation strategy the delegate provides.
 */
internal class SecureBufferFactory(
    private val delegate: BufferFactory,
) : BufferFactory {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer = SecureBuffer(zeroInit(delegate.allocate(size, byteOrder)))

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = SecureBuffer(delegate.wrap(array, byteOrder))

    /**
     * Zero-initializes the full backing of a freshly allocated buffer — the allocate-time mirror
     * of [SecureBuffer]'s wipe-on-free. Secure scratch must be deterministically zero on every
     * platform: crypto code (e.g. HKDF's empty-salt zero block) relies on a fresh secure buffer
     * reading as zero, and the native Linux backing ([com.ditchoom.buffer.NativeBuffer], raw
     * `malloc`) does not zero on its own. Zeros the whole capacity (not just `remaining()`) so no
     * slack bytes carry prior contents, then restores the position/limit the delegate handed back
     * so the `allocate` contract is unchanged (including pooled delegates where capacity > size).
     */
    private fun zeroInit(buffer: PlatformBuffer): PlatformBuffer {
        val savedPosition = buffer.position()
        val savedLimit = buffer.limit()
        buffer.position(0)
        buffer.setLimit(buffer.capacity)
        buffer.fill(0)
        buffer.position(savedPosition)
        buffer.setLimit(savedLimit)
        return buffer
    }
}

/**
 * Returns a factory whose buffers zero their backing storage when freed (see [SecureBuffer]).
 *
 * Layer it over the allocation strategy you want; for key material prefer a deterministic
 * delegate so the wipe is guaranteed to run:
 * ```kotlin
 * val secure = BufferFactory.deterministic().secure()
 * secure.allocate(32).use { key -> /* ... */ } // zeroed at end of block
 * ```
 */
fun BufferFactory.secure(): BufferFactory = SecureBufferFactory(this)
