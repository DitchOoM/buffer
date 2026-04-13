package com.ditchoom.buffer

/**
 * Android instrumented tests run on a real device/emulator where the JNI
 * NewDirectByteBuffer path is available. Deterministic allocation is supported.
 */
internal actual val isDeterministicAllocateSupported: Boolean = true
