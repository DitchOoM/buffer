package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.CodecKey
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
 * `DecodeContext` / `EncodeContext` key for the consumer-supplied
 * [BufferFactory] codecs should use when allocating consumer-owned
 * buffers. Read in [BitmapCodec] and [BinaryDataCodec]; defaults to
 * [testFixtureFactory] when absent.
 *
 * Canonical context key for the buffer-codec lockdown's Pattern #2 (the
 * consumer picks the factory; the codec allocates via it). Lives in the
 * test-fixture module for now; promote to `buffer-codec` if it gets used
 * more widely.
 */
object BufferFactoryKey : CodecKey<BufferFactory>

internal fun DecodeContext.bufferFactoryOrDefault(): BufferFactory = get(BufferFactoryKey) ?: testFixtureFactory

internal fun EncodeContext.bufferFactoryOrDefault(): BufferFactory = get(BufferFactoryKey) ?: testFixtureFactory
