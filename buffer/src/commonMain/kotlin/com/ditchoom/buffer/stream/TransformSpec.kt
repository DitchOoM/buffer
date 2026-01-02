package com.ditchoom.buffer.stream

/**
 * Interface for stream transform specifications.
 *
 * External modules (like buffer-compression) implement this interface
 * to provide transformations that can be composed via StreamProcessorBuilder.
 *
 * Example implementation:
 * ```kotlin
 * class DecompressionSpec(private val algorithm: CompressionAlgorithm) : TransformSpec {
 *     override fun wrapSync(inner: StreamProcessor) =
 *         DecompressingStreamProcessor(inner, algorithm)
 *
 *     override fun wrapSuspending(inner: SuspendingStreamProcessor) =
 *         SuspendingDecompressingStreamProcessor(inner, algorithm)
 * }
 * ```
 */
interface TransformSpec {
    /**
     * Wraps a synchronous StreamProcessor with this transform.
     *
     * @throws UnsupportedOperationException if this transform is async-only (e.g., JS CompressionStream)
     */
    fun wrapSync(inner: StreamProcessor): StreamProcessor

    /**
     * Wraps an async SuspendingStreamProcessor with this transform.
     * This method should always be implemented as suspending APIs work everywhere.
     */
    fun wrapSuspending(inner: SuspendingStreamProcessor): SuspendingStreamProcessor
}
