package com.ditchoom.buffer

/**
 * Whether this platform supports deterministic buffer allocation in tests.
 *
 * False only for Android unit tests (Robolectric) where Unsafe DirectByteBuffer
 * wrapping is not available on the host JVM. Real Android validation runs on
 * the emulator via androidInstrumentedTest.
 */
internal expect val isDeterministicAllocateSupported: Boolean

/**
 * Allocates a deterministic buffer, or returns null on platforms where
 * deterministic allocation is not supported in the test environment.
 */
internal fun deterministicAllocateOrSkip(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer? {
    if (!isDeterministicAllocateSupported) return null
    return BufferFactory.deterministic().allocate(size, byteOrder)
}
