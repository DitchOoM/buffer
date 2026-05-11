package com.ditchoom.buffer

/**
 * Apple: `NSDataBuffer.readByteArray` and `MutableDataBuffer.readByteArray`
 * both call `(bytePointer + position)!!.readBytes(size)` — the K/N stdlib's
 * `CPointer<ByteVar>.readBytes(count)` returns a freshly allocated
 * `ByteArray`. `ByteArrayBuffer` (used for wrapped Kotlin arrays) copies via
 * `data.copyOfRange`. Independence holds.
 */
internal actual val readByteArrayAliasesSource: Boolean = false
