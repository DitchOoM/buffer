package com.ditchoom.buffer

/**
 * See [hexStringOf]. One `append(String)` per byte from the pair table beats two `append(Char)` by
 * 1.82x on JS. Stated separately from the nonJvm actual because jsMain is not part of nonJvmMain.
 */
internal actual fun ReadBuffer.hexStringOf(
    offset: Int,
    length: Int,
    upperCase: Boolean,
): String = hexStringViaPairTable(offset, length, upperCase)
