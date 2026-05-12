package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.BufferFactoryKey
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Default [BufferFactory] for codec test fixtures.
 *
 * Picks `BufferFactory.Default` on platforms whose Default backend is
 * cleaner-/GC-managed (JVM `DirectByteBuffer` via cleaner, JS / WasmJs GC,
 * Apple `NSData` ARC). On Linux native, `BufferFactory.Default` is
 * `NativeBuffer` (malloc/free) which leaks if never explicitly closed —
 * test fixtures hold the buffer for the lifetime of the typed handle with
 * no `close()` story, so on Linux specifically we fall back to
 * `BufferFactory.managed()` (heap-backed, GC-cleaned).
 *
 * Test code that wants to override the choice (e.g. exercise the Default
 * path on Linux deliberately, or test the deterministic factory) supplies
 * a [BufferFactoryKey] entry in [DecodeContext].
 */
internal expect val testFixtureFactory: BufferFactory

/**
 * Test-side fallback to [testFixtureFactory] when [BufferFactoryKey] is absent.
 * Production codecs in `buffer-codec` fall back to `BufferFactory.Default`; tests
 * fall back to [testFixtureFactory] to dodge Linux's leak-prone default.
 */
internal fun DecodeContext.bufferFactoryOrDefault(): BufferFactory = get(BufferFactoryKey) ?: testFixtureFactory

internal fun EncodeContext.bufferFactoryOrDefault(): BufferFactory = get(BufferFactoryKey) ?: testFixtureFactory
