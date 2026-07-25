package com.ditchoom.buffer

/**
 * See [hexStringOf]. The JVM's `append(Char)` writes two chars straight into the builder's storage,
 * which beats one `append(String)` from the pair table by 1.71x here — the opposite of every other
 * target.
 */
internal actual fun ReadBuffer.hexStringOf(
    offset: Int,
    length: Int,
    upperCase: Boolean,
): String = hexStringViaCharAppend(offset, length, upperCase)
