@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

/**
 * FFM-based factory for Java 21+.
 * Returns FfmBufferScope which uses Arena for deterministic memory management.
 */
@PublishedApi
internal fun createBufferScope(): BufferScope = FfmBufferScope()
