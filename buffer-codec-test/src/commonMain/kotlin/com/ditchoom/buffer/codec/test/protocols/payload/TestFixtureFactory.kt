package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.BufferFactoryKey
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Default [BufferFactory] for codec test fixtures.
 *
 * `BufferFactory.Default` is GC- / ARC- / cleaner-managed on every supported
 * platform — JVM `DirectByteBuffer` via cleaner, JS / WasmJs GC, Apple
 * `NSData` ARC, Linux native heap (Default routes to `managedBufferFactory`
 * since the V2 migration; previous malloc/free `NativeBuffer` is now opt-in
 * via `PlatformBuffer.allocateNative` or `deterministic()`).
 *
 * Test code that wants to override the choice (exercise the deterministic
 * factory, force shared memory, etc.) supplies a [BufferFactoryKey] entry
 * in [DecodeContext].
 */
internal val testFixtureFactory: BufferFactory = BufferFactory.Default

/**
 * Test-side fallback to [testFixtureFactory] when [BufferFactoryKey] is absent.
 */
internal fun DecodeContext.bufferFactoryOrDefault(): BufferFactory = get(BufferFactoryKey) ?: testFixtureFactory

internal fun EncodeContext.bufferFactoryOrDefault(): BufferFactory = get(BufferFactoryKey) ?: testFixtureFactory
