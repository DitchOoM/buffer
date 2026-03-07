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
 * class MqttConnection(
 *     val factory: BufferFactory = BufferFactory.Default,
 * ) {
 *     fun send(packet: MqttPacket) {
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
     * @param size The buffer capacity in bytes
     * @param byteOrder The byte order for multi-byte operations
     * @return A new [PlatformBuffer] with position at 0 and limit at [size]
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

    companion object
}

// =============================================================================
// Platform presets — expect/actual wiring
// =============================================================================

/**
 * Platform-optimal native memory factory.
 *
 * Returns direct/native buffers on platforms that support them:
 * - **JVM**: DirectByteBuffer
 * - **Android**: DirectByteBuffer
 * - **Apple**: MutableDataBuffer (NSMutableData)
 * - **JS**: JsBuffer (Int8Array)
 * - **WASM**: LinearBuffer (WASM linear memory)
 * - **Linux**: NativeBuffer (malloc)
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
 * Platform-optimal native memory.
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
            buffer.freeNativeMemory()
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
            buffer.freeNativeMemory()
            throw UnsupportedOperationException(
                "BufferFactory.requiring<${capability.simpleName}>(): " +
                    "wrapped buffer ${buffer::class.simpleName} does not implement ${capability.simpleName}",
            )
        }
        return buffer
    }
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
        if (buffer is PlatformBuffer && buffer.byteOrder == byteOrder) return buffer
        // Byte order mismatch or not a PlatformBuffer — release back and allocate fresh
        pool.release(buffer)
        return delegate.allocate(size, byteOrder)
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = delegate.wrap(array, byteOrder)
}
