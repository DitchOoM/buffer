package com.ditchoom.buffer

/*
 * Hex <-> String conversions.
 *
 * The buffer-to-buffer primitives live in HexCodec.kt and are the right tool for hex onto a wire.
 * These are for the call sites whose result genuinely has to *be* a String — a log line, an assertion
 * message, a toString(), a JSON field. Neither direction stages an intermediate buffer.
 */

/**
 * Encodes one nibble straight to its ASCII hex `Char` — the same branchless math as [hexEncodeNibble],
 * so the `String` form can never drift from the buffer form.
 *
 * Deliberately **not** `Char(code)`: that constructor emits a `0..0xFFFF` range check plus a cold
 * exception-message builder, and this runs twice per byte in [toHexString]'s inner loop. The check is
 * dead by construction — [hexEncodeNibble] returns an ASCII digit in `0x30..0x66` — so the direct
 * widening (a single `i2c` on the JVM) is what we want.
 */
@Suppress("DEPRECATION")
private fun hexEncodeNibbleChar(
    nibble: Int,
    alphaDelta: Int,
): Char = hexEncodeNibble(nibble, alphaDelta).toInt().toChar()

/**
 * Maps a single hex `Char` to its 0..15 nibble value, or -1 if it is not a hex digit. Anything outside
 * ASCII is rejected up front: narrowing a `Char` to a `Byte` first would fold e.g. U+0161 onto `'a'`
 * and silently decode a non-hex character as 10.
 */
private fun hexDecodeNibble(c: Char): Int = if (c.code > MAX_ASCII) -1 else hexDecodeNibble(c.code.toByte())

/** Largest code point that is still ASCII; above it, a `Char` cannot be a hex digit. */
private const val MAX_ASCII = 0x7F

/** Number of distinct byte values, i.e. the size of the two-char pair tables. */
private const val BYTE_VALUE_COUNT = 256

/**
 * The 256 two-character hex strings, one per byte value, in each case.
 *
 * Lives in an `object` rather than as a top-level `val` so the 512 small strings are built on first
 * use of the `String` conversions, not on first touch of anything in this file — callers who only ever
 * use the buffer-to-buffer [encodeHexInto] never pay for it.
 */
private object HexPairs {
    val lower: Array<String> = build(HEX_LOWER_ALPHA_DELTA)
    val upper: Array<String> = build(HEX_UPPER_ALPHA_DELTA)

    private fun build(alphaDelta: Int): Array<String> =
        Array(BYTE_VALUE_COUNT) { b ->
            "" + hexEncodeNibbleChar(b ushr NIBBLE_BITS, alphaDelta) +
                hexEncodeNibbleChar(b and LOW_NIBBLE_MASK, alphaDelta)
        }
}

/**
 * Selects the encode body for this platform. Both bodies produce identical output from the same
 * [hexEncodeNibble] math; they differ only in how they feed the `StringBuilder`, and the platforms
 * disagree sharply about which is cheaper (measured at 1 KiB, direct-vs-direct):
 *
 * | | JVM | JS | WasmJs | linuxX64 |
 * |---|---|---|---|---|
 * | [hexStringViaCharAppend] | **387,680** | 142,396 | 37,365 | 120,435 |
 * | [hexStringViaPairTable] | 226,302 | **258,791** | **190,688** | **143,653** |
 *
 * `append(Char)` writes two chars straight into the builder's storage, which the JVM does better than
 * anything else; everywhere else the per-append overhead dominates and halving the append count wins —
 * by 5.1x on WasmJs. Same shape as [encodeHexInto], which the native backends already override.
 */
internal expect fun ReadBuffer.hexStringOf(
    offset: Int,
    length: Int,
    upperCase: Boolean,
): String

/** Two `append(Char)` per byte. Fastest on the JVM. See [hexStringOf]. */
internal fun ReadBuffer.hexStringViaCharAppend(
    offset: Int,
    length: Int,
    upperCase: Boolean,
): String {
    val alphaDelta = if (upperCase) HEX_UPPER_ALPHA_DELTA else HEX_LOWER_ALPHA_DELTA
    return buildString(length * 2) {
        var i = 0
        while (i < length) {
            val b = getUnchecked(offset + i).toInt() and BufferConstants.BYTE_MASK
            append(hexEncodeNibbleChar(b ushr NIBBLE_BITS, alphaDelta))
            append(hexEncodeNibbleChar(b and LOW_NIBBLE_MASK, alphaDelta))
            i++
        }
    }
}

/** One `append(String)` per byte from [HexPairs]. Fastest everywhere but the JVM. See [hexStringOf]. */
internal fun ReadBuffer.hexStringViaPairTable(
    offset: Int,
    length: Int,
    upperCase: Boolean,
): String {
    val pairs = if (upperCase) HexPairs.upper else HexPairs.lower
    return buildString(length * 2) {
        var i = 0
        while (i < length) {
            append(pairs[getUnchecked(offset + i).toInt() and BufferConstants.BYTE_MASK])
            i++
        }
    }
}

/**
 * Hex-encodes the absolute source range `[offset, offset + length)` and returns it as a `String`.
 *
 * The buffer-to-buffer [encodeHexInto] is the primitive for hex onto a wire; this is the form for the
 * cases whose result genuinely has to *be* a `String` — a log line, an assertion message, a
 * `toString()`, a JSON field. Without it every such call site hand-rolls the same "allocate a
 * destination, encode, read it back" dance, and the ones that don't instead write a per-byte format
 * loop, which is precisely the pattern this library exists to avoid.
 *
 * **No buffer is allocated**, and so no [BufferFactory] is taken. Staging through an intermediate ASCII
 * buffer to reach the bulk path would cost an allocation the caller then has to free — and on WasmJs
 * cannot free, since that target's default allocator is a non-reclaiming bump allocator over a fixed
 * arena. It is also slower on three of the four measured targets (see [hexStringOf]). Callers who do
 * want the bulk path are already served — they want hex in a buffer, which is [encodeHexInto].
 *
 * Does not change this buffer's [position].
 *
 * @param offset absolute index of the first source byte
 * @param length number of source bytes to encode
 * @param upperCase emit 'A'-'F' instead of 'a'-'f'
 * @throws BufferUnderflowException if `[offset, offset + length)` is not within this buffer.
 */
fun ReadBuffer.toHexString(
    offset: Int,
    length: Int,
    upperCase: Boolean = false,
): String {
    requireRange(offset, length)
    if (length == 0) return ""
    return hexStringOf(offset, length, upperCase)
}

/**
 * Relative: hex-encodes this buffer's remaining bytes (`position()` until `limit()`) and returns them
 * as a `String`. Unlike the relative [encodeHexInto], this does **not** advance [position] — a value
 * conversion should not consume its receiver, so the same buffer can be logged and then still read.
 *
 * @param upperCase emit 'A'-'F' instead of 'a'-'f'
 */
fun ReadBuffer.toHexString(upperCase: Boolean = false): String = toHexString(position(), remaining(), upperCase)

/**
 * Hex-decodes [hex] (an even count of ASCII hex characters) into a buffer allocated by this factory,
 * returned read-ready with its limit at the decoded length. The inverse of [toHexString], and the
 * counterpart every test fixture and config parser that writes a hex literal needs.
 *
 * The factory is the **receiver**, not a parameter: the one allocation this makes is the buffer being
 * returned, so a caller with a pooled or deterministic allocator gets the result on their own memory,
 * and the call reads as what it is — a factory producing a buffer. Nothing is staged, so there is no
 * intermediate to leak: the characters are decoded directly out of [hex].
 *
 * @param hex the ASCII hex characters; must be an even count
 * @param byteOrder byte order of the returned buffer
 * @throws IllegalArgumentException if [hex] has an odd length or contains a non-hex character.
 */
fun BufferFactory.fromHexString(
    hex: CharSequence,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer {
    // Hoisted: `hex.length` on a CharSequence receiver is a dispatching helper call on the JS/WASM
    // backends, so leaving it in the loop condition would pay for it once per character.
    val n = hex.length
    require(n % 2 == 0) { "hex length must be even, got $n" }
    val decoded = allocate(n / 2, byteOrder)
    // Indexing a String is direct; indexing a bare CharSequence goes through a dispatching helper per
    // character on the JS/WASM backends. Splitting on the smart cast is worth +39% on JS and +60% on
    // WasmJs, and is a single folded instanceof on the JVM. Both arms are the same inlined loop.
    if (hex is String) {
        decodeHexCharsInto(n, decoded) { hex[it] }
    } else {
        decodeHexCharsInto(n, decoded) { hex[it] }
    }
    decoded.resetForRead()
    return decoded
}

/**
 * Decodes [length] hex characters supplied by [charAt] into [dest]. Inline so the caller's indexing
 * expression is compiled in place — that is the whole point of the `String` split in [fromHexString].
 *
 * @throws IllegalArgumentException if a character is not a hex digit.
 */
private inline fun decodeHexCharsInto(
    length: Int,
    dest: WriteBuffer,
    charAt: (Int) -> Char,
) {
    var i = 0
    while (i < length) {
        val hi = hexDecodeNibble(charAt(i))
        val lo = hexDecodeNibble(charAt(i + 1))
        require(hi >= 0) { "invalid hex character at index $i" }
        require(lo >= 0) { "invalid hex character at index ${i + 1}" }
        dest.writeByte(((hi shl NIBBLE_BITS) or lo).toByte())
        i += 2
    }
}
