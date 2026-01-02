package com.ditchoom.buffer.stream

import com.ditchoom.buffer.pool.BufferPool

/**
 * Builder for creating StreamProcessors with composable transforms.
 *
 * External modules add extension functions to this builder to provide transforms:
 * ```kotlin
 * // In buffer-compression module
 * fun StreamProcessorBuilder.decompress(
 *     algorithm: CompressionAlgorithm = CompressionAlgorithm.Gzip
 * ): StreamProcessorBuilder = addTransform(DecompressionSpec(algorithm))
 * ```
 *
 * Usage:
 * ```kotlin
 * // Sync processor (throws on JS for async-only transforms)
 * val processor = StreamProcessor.builder(pool)
 *     .decompress(Gzip)
 *     .build()
 *
 * // Async processor (works everywhere)
 * val suspendingProcessor = StreamProcessor.builder(pool)
 *     .decompress(Gzip)
 *     .buildSuspending()
 * ```
 */
class StreamProcessorBuilder(
    private val pool: BufferPool,
) {
    private val transforms = mutableListOf<TransformSpec>()

    /**
     * Adds a transform to the processing pipeline.
     * Transforms are applied in order: first added = outermost wrapper.
     *
     * @return this builder for chaining
     */
    fun addTransform(spec: TransformSpec): StreamProcessorBuilder {
        transforms.add(spec)
        return this
    }

    /**
     * Builds a synchronous StreamProcessor with all transforms applied.
     *
     * @throws UnsupportedOperationException if any transform is async-only
     */
    fun build(): StreamProcessor {
        var processor = StreamProcessor.create(pool)

        // Apply transforms in order (first added = outermost)
        for (spec in transforms) {
            processor = spec.wrapSync(processor)
        }

        return processor
    }

    /**
     * Builds a suspending StreamProcessor with all transforms applied.
     * This always works, even with async-only transforms like JS CompressionStream.
     */
    fun buildSuspending(): SuspendingStreamProcessor {
        var processor: SuspendingStreamProcessor = SyncToSuspendingProcessor(StreamProcessor.create(pool))

        // Apply transforms in order (first added = outermost)
        for (spec in transforms) {
            processor = spec.wrapSuspending(processor)
        }

        return processor
    }
}

/**
 * Creates a StreamProcessorBuilder for composing transforms.
 */
fun StreamProcessor.Companion.builder(pool: BufferPool): StreamProcessorBuilder = StreamProcessorBuilder(pool)
