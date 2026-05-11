package com.ditchoom.buffer

/**
 * JS: `JsBuffer.readByteArray` returns
 * `Int8Array(subArray.buffer, subArray.byteOffset, size).unsafeCast<ByteArray>()`
 * — a view over the source's underlying ArrayBuffer. Mutations to the source
 * are visible through the returned array.
 *
 * This is the platform reality `copyToByteArray` (Change 3) was added to
 * paper over: consumers needing independence across the codec boundary use
 * `copyToByteArray` rather than `readByteArray`.
 */
internal actual val readByteArrayAliasesSource: Boolean = true
