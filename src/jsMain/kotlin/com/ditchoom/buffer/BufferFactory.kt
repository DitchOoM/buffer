@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

actual fun allocateNewBuffer(
    size: UInt
): PlatformBuffer {
    return JsBuffer(Uint8Array(size.toInt()))
}

actual fun String.toBuffer(): PlatformBuffer {
    val int8Array = encodeToByteArray().unsafeCast<Int8Array>()
    val uint8Array = Uint8Array(int8Array.buffer)
    return JsBuffer(uint8Array)
}

actual fun String.utf8Length(): UInt = encodeToByteArray().unsafeCast<Int8Array>().length.toUInt()