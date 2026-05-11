package com.ditchoom.buffer

/**
 * `BufferFactory.deterministic()` on Android prefers JNI's
 * `NewDirectByteBuffer` (see `JniDirectByteBufferAllocator` +
 * `src/androidMain/cpp/buffer_jni.cpp`), which is a public JNI function not
 * subject to hidden-API enforcement on non-debuggable test APKs. The
 * reflective fallback remains for environments that omit the native ABI.
 * With the JNI path wired in, the deterministic factory is available on
 * any modern emulator/device.
 */
internal actual val isDeterministicAllocateSupported: Boolean = true
