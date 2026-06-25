package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool

/**
 * Factory for creating [PlatformBuffer] instances with a specific allocation strategy.
 *
 * `BufferFactory` provides a composable, extensible factory pattern for buffer allocation.
 * Instead of passing a zone parameter, you choose (or create) a factory that encapsulates
 * the allocation strategy.
 *
 * ## Built-in presets
 *
 * ```kotlin
 * // Platform-optimal native memory (DirectByteBuffer, NSMutableData, malloc, etc.)
 * val buf = BufferFactory.Default.allocate(1024)
 *
 * // GC-managed heap memory (ByteArray-backed)
 * val buf = BufferFactory.managed().allocate(1024)
 *
 * // Cross-process shared memory (SharedMemory on Android, SharedArrayBuffer on JS)
 * val buf = BufferFactory.shared().allocate(1024)
 * ```
 *
 * ## Composable decorators
 *
 * Layer behaviors on top of any factory:
 * ```kotlin
 * val factory = BufferFactory.Default
 *     .requiring<NativeMemoryAccess>()   // throw if buffer lacks native access
 *     .withSizeLimit(1_048_576)          // cap allocation size
 *     .withPooling(pool)                 // recycle buffers
 * ```
 *
 * ## Custom factories
 *
 * Implement the interface directly or delegate:
 * ```kotlin
 * class MonitoredFactory(
 *     private val delegate: BufferFactory = BufferFactory.Default,
 * ) : BufferFactory by delegate {
 *     override fun allocate(size: Int, byteOrder: ByteOrder): PlatformBuffer {
 *         metrics.recordAllocation(size)
 *         return delegate.allocate(size, byteOrder)
 *     }
 * }
 * ```
 *
 * ## Library author pattern
 *
 * Accept a factory parameter so callers can control allocation:
 * ```kotlin
 * class ProtocolConnection(
 *     val factory: BufferFactory = BufferFactory.Default,
 * ) {
 *     fun send(packet: Packet) {
 *         factory.allocate(bufferSize).use { buffer ->
 *             packet.writeTo(buffer)
 *         }
 *     }
 * }
 * ```
 *
 * @see BufferFactory.Default Platform-optimal allocation
 * @see BufferFactory.managed GC-managed heap allocation
 * @see BufferFactory.shared Cross-process shared memory allocation
 */
interface BufferFactory {
    /**
     * Allocates a new buffer of the specified size.
     *
     * @param size The buffer capacity in bytes. Must be non-negative.
     * @param byteOrder The byte order for multi-byte operations
     * @return A new [PlatformBuffer] with position at 0 and limit at [size]
     * @throws IllegalArgumentException if [size] is negative
     */
    fun allocate(
        size: Int,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): PlatformBuffer

    /**
     * Wraps an existing byte array in a buffer (zero-copy where possible).
     *
     * The returned buffer shares memory with the original array — modifications
     * to one are visible in the other.
     *
     * @param array The byte array to wrap
     * @param byteOrder The byte order for multi-byte operations
     * @return A [PlatformBuffer] backed by the given array
     */
    fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): PlatformBuffer

    /**
     * Wraps an **already-allocated** [buffer] in this factory's per-buffer decoration, if any.
     *
     * The default is the identity function: most factories add their behavior at allocation
     * time and have nothing to layer onto a pre-existing buffer. Only *wrapping* decorators —
     * factories whose contribution is a [PlatformBuffer] wrapper rather than an allocation
     * strategy — override this. The canonical example is the secure-erase factory, whose
     * `decorate` returns a wrapper that zeroes the backing on free.
     *
     * This exists so a wrapping decorator's behavior is preserved even when the buffer does
     * not come from its own `allocate`. In particular [withPooling] borrows a buffer from a
     * pool and then calls `delegate.decorate(borrowed)`, so `secure().withPooling(pool)` and
     * `withPooling(pool).secure()` both yield a secure-wrapped pooled buffer (wipe-on-free,
     * then return-to-pool) — the composition order stops mattering.
     *
     * Contract: `decorate` must **wrap an existing buffer, never allocate**. Transparent
     * decorators that add no per-buffer wrapper should forward to their delegate's `decorate`
     * so an inner wrapping decorator still gets a chance to wrap.
     *
     * @param buffer an existing buffer to decorate
     * @return [buffer] wrapped in this factory's decoration, or [buffer] unchanged
     */
    fun decorate(buffer: PlatformBuffer): PlatformBuffer = buffer

    companion object
}

// =============================================================================
// Platform presets — expect/actual wiring
// =============================================================================

/**
 * Safe, GC-managed, no-leak-risk factory.
 *
 * Returns buffers that are garbage-collected on all platforms — no explicit cleanup required:
 * - **JVM 21+**: FfmAutoBuffer (Arena.ofAuto(), GC-collected)
 * - **JVM < 21**: DirectJvmBuffer (GC via Cleaner)
 * - **Android**: DirectJvmBuffer (GC via Cleaner)
 * - **Apple**: MutableDataBuffer (NSMutableData, ARC-managed)
 * - **JS**: JsBuffer (Int8Array, GC-managed)
 * - **WASM**: LinearBuffer (WASM linear memory)
 * - **Linux**: ByteArrayBuffer (GC-managed)
 */
internal expect val defaultBufferFactory: BufferFactory

/**
 * GC-managed heap memory factory.
 *
 * Returns buffers backed by Kotlin ByteArrays, managed by the garbage collector.
 * No explicit cleanup required.
 */
internal expect val managedBufferFactory: BufferFactory

/**
 * Cross-process shared memory factory.
 *
 * Returns buffers backed by shared memory where available:
 * - **Android**: SharedMemory (API 27+), falls back to direct
 * - **JS**: SharedArrayBuffer, falls back to regular ArrayBuffer
 * - **Other platforms**: Falls back to [defaultBufferFactory]
 */
internal expect val sharedBufferFactory: BufferFactory

/**
 * Deterministic cleanup factory.
 *
 * Returns buffers that implement [CloseableBuffer] for guaranteed resource cleanup,
 * independent of garbage collection. Callers must use `buffer.use {}` or call
 * [PlatformBuffer.freeNativeMemory] explicitly.
 *
 * @param threadConfined If true, uses a thread-confined arena (JVM 21+ only: Arena.ofConfined()).
 *   On other platforms this parameter is ignored.
 *
 * Platform implementations:
 * - **JVM 21+**: FfmBuffer with Arena.ofShared() (default) or Arena.ofConfined() (threadConfined=true)
 * - **JVM 9-20**: DeterministicDirectJvmBuffer (DirectByteBuffer + Unsafe.invokeCleaner)
 * - **JVM 8 / Android**: DeterministicUnsafeJvmBuffer (Unsafe.allocateMemory/freeMemory)
 * - **Apple**: MutableDataBuffer (ARC-managed, already deterministic)
 * - **Linux**: NativeBuffer (malloc/free)
 * - **WASM**: LinearBuffer (linear memory, already deterministic)
 * - **JS**: JsBuffer (GC-managed, no deterministic alternative)
 */
internal expect fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory

/**
 * Safe, GC-managed, no-leak-risk buffers. No explicit cleanup required.
 */
val BufferFactory.Companion.Default: BufferFactory get() = defaultBufferFactory

/**
 * GC-managed heap memory.
 */
fun BufferFactory.Companion.managed(): BufferFactory = managedBufferFactory

/**
 * Cross-process shared memory. Falls back to [Default] where unavailable.
 */
fun BufferFactory.Companion.shared(): BufferFactory = sharedBufferFactory

/**
 * Deterministic cleanup — buffers implement [CloseableBuffer].
 *
 * Use with `buffer.use {}` for automatic cleanup:
 * ```kotlin
 * BufferFactory.deterministic().allocate(1024).use { buffer ->
 *     buffer.writeInt(42)
 * } // freed immediately, no GC needed
 * ```
 *
 * @param threadConfined If true, uses a thread-confined arena on JVM 21+
 *   ([Arena.ofConfined][java.lang.foreign.Arena.ofConfined]). Ignored on other platforms.
 *
 * ktlint (no .editorconfig) collapses this expression body onto one line, so it cannot be wrapped.
 */
@Suppress("MaxLineLength")
fun BufferFactory.Companion.deterministic(threadConfined: Boolean = false): BufferFactory = deterministicBufferFactory(threadConfined)

// =============================================================================
// Composable decorators
// =============================================================================

/**
 * Returns a factory that throws [UnsupportedOperationException] if an allocated
 * buffer does not implement the requested capability interface [T].
 *
 * ```kotlin
 * val factory = BufferFactory.Default.requiring<NativeMemoryAccess>()
 * val buf = factory.allocate(1024) // throws if no native access
 * ```
 *
 * @param T The capability interface to require (e.g., [NativeMemoryAccess], [ManagedMemoryAccess])
 */
inline fun <reified T> BufferFactory.requiring(): BufferFactory = RequiringFactory(this, T::class)

/**
 * Returns a factory that tries to produce buffers with the requested capability [T].
 *
 * If the primary factory doesn't produce a buffer with the capability, the decorator
 * tries the opposite strategy (native ↔ managed) as a fallback. If neither works,
 * returns the best available buffer without throwing.
 *
 * ```kotlin
 * val factory = BufferFactory.Default.preferring<NativeMemoryAccess>()
 * val buf = factory.allocate(1024) // native if possible, heap otherwise
 * ```
 *
 * @param T The preferred capability interface
 */
inline fun <reified T> BufferFactory.preferring(): BufferFactory = PreferringFactory(this, T::class)

/**
 * Returns a factory that caps allocation size to [maxBytes].
 *
 * Throws [IllegalArgumentException] if a caller requests more than [maxBytes].
 *
 * ```kotlin
 * val factory = BufferFactory.Default.withSizeLimit(1_048_576) // 1 MB max
 * factory.allocate(2_000_000) // throws IllegalArgumentException
 * ```
 */
fun BufferFactory.withSizeLimit(maxBytes: Int): BufferFactory = SizeLimitedFactory(this, maxBytes)

/**
 * Returns a factory that acquires buffers from the given [pool] instead of
 * allocating new ones. The returned buffers are [PooledBuffer][com.ditchoom.buffer.pool.PooledBuffer]
 * instances whose [freeNativeMemory][PlatformBuffer.freeNativeMemory] returns them to the pool.
 *
 * ```kotlin
 * val pool = BufferPool()
 * val factory = BufferFactory.Default.withPooling(pool)
 * val buf = factory.allocate(1024) // acquired from pool
 * buf.freeNativeMemory()           // returned to pool
 * ```
 */
fun BufferFactory.withPooling(pool: BufferPool): BufferFactory = PoolingFactory(this, pool)

// =============================================================================
// Decorator implementations
// =============================================================================

@PublishedApi
internal class RequiringFactory(
    private val delegate: BufferFactory,
    private val capability: kotlin.reflect.KClass<*>,
) : BufferFactory {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = delegate.allocate(size, byteOrder)
        val unwrapped = buffer.unwrapFully()
        if (!capability.isInstance(buffer) && !capability.isInstance(unwrapped)) {
            try {
                buffer.freeNativeMemory()
            } catch (_: Exception) {
            }
            throw UnsupportedOperationException(
                "BufferFactory.requiring<${capability.simpleName}>(): " +
                    "allocated buffer ${buffer::class.simpleName} does not implement ${capability.simpleName}",
            )
        }
        return buffer
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = delegate.wrap(array, byteOrder)
        val unwrapped = buffer.unwrapFully()
        if (!capability.isInstance(buffer) && !capability.isInstance(unwrapped)) {
            try {
                buffer.freeNativeMemory()
            } catch (_: Exception) {
            }
            throw UnsupportedOperationException(
                "BufferFactory.requiring<${capability.simpleName}>(): " +
                    "wrapped buffer ${buffer::class.simpleName} does not implement ${capability.simpleName}",
            )
        }
        return buffer
    }

    // Transparent decorator: forward so an inner wrapping decorator (e.g. secure()) can wrap.
    override fun decorate(buffer: PlatformBuffer): PlatformBuffer = delegate.decorate(buffer)
}

@PublishedApi
internal class PreferringFactory(
    private val delegate: BufferFactory,
    private val capability: kotlin.reflect.KClass<*>,
) : BufferFactory {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = delegate.allocate(size, byteOrder)
        val unwrapped = buffer.unwrapFully()
        if (capability.isInstance(buffer) || capability.isInstance(unwrapped)) return buffer

        // Try the opposite strategy as fallback
        val fallback =
            if (delegate === defaultBufferFactory) {
                managedBufferFactory
            } else {
                defaultBufferFactory
            }
        val fallbackBuffer = fallback.allocate(size, byteOrder)
        val fallbackUnwrapped = fallbackBuffer.unwrapFully()
        return if (capability.isInstance(fallbackBuffer) || capability.isInstance(fallbackUnwrapped)) {
            buffer.freeNativeMemory()
            fallbackBuffer
        } else {
            fallbackBuffer.freeNativeMemory()
            buffer
        }
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = delegate.wrap(array, byteOrder)

    // Transparent decorator: forward so an inner wrapping decorator (e.g. secure()) can wrap.
    override fun decorate(buffer: PlatformBuffer): PlatformBuffer = delegate.decorate(buffer)
}

internal class SizeLimitedFactory(
    private val delegate: BufferFactory,
    private val maxBytes: Int,
) : BufferFactory {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        require(size <= maxBytes) {
            "Requested allocation of $size bytes exceeds limit of $maxBytes bytes"
        }
        return delegate.allocate(size, byteOrder)
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        require(array.size <= maxBytes) {
            "Array size of ${array.size} bytes exceeds limit of $maxBytes bytes"
        }
        return delegate.wrap(array, byteOrder)
    }

    // Transparent decorator: the size guard is allocation-time only; forward so an inner
    // wrapping decorator (e.g. secure()) can wrap a borrowed buffer.
    override fun decorate(buffer: PlatformBuffer): PlatformBuffer = delegate.decorate(buffer)
}

internal class PoolingFactory(
    private val delegate: BufferFactory,
    private val pool: BufferPool,
) : BufferFactory {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = pool.acquire(size)
        // Apply the delegate's per-buffer decoration to the pool-borrowed buffer. For a plain
        // delegate this is identity; for a wrapping delegate like secure() it returns e.g.
        // SecureBuffer(zeroInit(pooled)) so freeNativeMemory() wipes the buffer *then* returns
        // it to the pool. This makes secure().withPooling(pool) wipe correctly — the secure
        // layer no longer has to be the outermost factory.
        if (buffer is PlatformBuffer && buffer.byteOrder == byteOrder) return delegate.decorate(buffer)
        // Byte order mismatch or not a PlatformBuffer — release back and allocate fresh.
        // delegate.allocate already applies the delegate's decoration (e.g. secure-wraps),
        // so this fallback path is decorated too; just not pooled.
        pool.release(buffer)
        return delegate.allocate(size, byteOrder)
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = delegate.wrap(array, byteOrder)

    // A pool cannot pool an externally-owned buffer, so decoration is the delegate's only
    // contribution here — forward it. (PoolingFactory itself adds no per-buffer wrapper.)
    override fun decorate(buffer: PlatformBuffer): PlatformBuffer = delegate.decorate(buffer)
}
