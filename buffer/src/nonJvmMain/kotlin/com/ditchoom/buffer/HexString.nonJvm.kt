package com.ditchoom.buffer

/**
 * See [hexStringOf]. Native and WasmJs both pay heavily per append, so halving the append count with
 * the pair table wins — 1.19x on linuxX64 and 5.10x on WasmJs.
 */
internal actual fun ReadBuffer.hexStringOf(
    offset: Int,
    length: Int,
    upperCase: Boolean,
): String = hexStringViaPairTable(offset, length, upperCase)
