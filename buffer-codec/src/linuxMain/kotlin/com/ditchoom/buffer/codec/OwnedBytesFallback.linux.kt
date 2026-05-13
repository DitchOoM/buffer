package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed

/**
 * Linux native fallback for [ownedBytesFallbackFactory]. Returns
 * [BufferFactory.managed] — Linux's [BufferFactory.Default] uses `malloc` /
 * `free`-backed `NativeBuffer`, which leaks when the [OwnedBytesHandle]
 * reference drops without an explicit cleanup. The managed factory returns
 * `ByteArrayBuffer` (heap-backed `ByteArray`), which the Kotlin/Native GC
 * reclaims — same correctness profile as JVM / JS / Apple, at the cost of
 * one heap copy.
 *
 * Production consumers wanting `NativeBuffer` allocation (zero-copy
 * io_uring path, FFI handoff) supply their own factory via
 * `DecodeContext.with(BufferFactoryKey, BufferFactory.Default)` and assume
 * responsibility for cleanup.
 */
actual fun ownedBytesFallbackFactory(): BufferFactory = BufferFactory.managed()
