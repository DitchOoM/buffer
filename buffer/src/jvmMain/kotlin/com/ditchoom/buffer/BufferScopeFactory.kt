package com.ditchoom.buffer

/**
 * Base factory for JVM < 21.
 * Returns UnsafeBufferScope which uses sun.misc.Unsafe for memory management.
 *
 * On Java 21+, this is replaced by the multi-release JAR version in META-INF/versions/21/
 * which returns FfmBufferScope using FFM Arena.
 */
@PublishedApi
internal fun createBufferScope(): BufferScope = UnsafeBufferScope()
