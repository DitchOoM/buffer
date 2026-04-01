package com.ditchoom.buffer

/**
 * Android unit tests run on the host JVM via Robolectric where Unsafe
 * DirectByteBuffer wrapping is not available. Deterministic buffer tests
 * are validated on real Android via androidInstrumentedTest instead.
 */
internal actual val isDeterministicAllocateSupported: Boolean = false
