package com.ditchoom.buffer

// Utility functions for parsing base-10 numbers out of textual digit bytes.
//
// Input is UTF-8 text. Digits ('0'-'9'), sign ('-') and the decimal point ('.') all live in
// the ASCII range, which UTF-8 encodes as the exact same single bytes — so these parsers handle
// UTF-8-encoded numbers transparently (ASCII is a strict byte-identical subset). They deliberately
// do NOT recognise non-ASCII Unicode numerals (Arabic-Indic ١٢٣, fullwidth １２３, …): those are
// multi-byte, never appear in machine-readable formats, and would force per-code-point decoding.
//
// These complement the binary readInt()/readLong() (which decode encoded values) by decoding the
// textual representation, e.g. the bytes '-','1','2','.','3' -> -123 tenths. Allocation-free: they
// read through the supplied absolute getByte accessor only.

/**
 * Parse the fixed 1BRC-style temperature format from an absolute byte range:
 * an optional leading '-', one or two integer digits, a '.', then exactly one
 * fractional digit. Returns the value scaled by 10 (tenths).
 *
 * Examples: "-12.3" -> -123, "5.0" -> 50, "0.0" -> 0.
 *
 * Because the format always carries exactly one fractional digit, accumulating
 * `value * 10 + digit` over every digit yields the tenths value directly.
 *
 * @param offset absolute index of the first byte
 * @param length number of bytes in the field
 * @param getByte absolute byte accessor
 */
internal inline fun parseFixedDecimalTenths(
    offset: Int,
    length: Int,
    getByte: (Int) -> Byte,
): Int {
    var i = offset
    val end = offset + length
    var negative = false
    if (length > 0 && getByte(i) == '-'.code.toByte()) {
        negative = true
        i++
    }
    var value = 0
    while (i < end) {
        val b = getByte(i)
        if (b != '.'.code.toByte()) {
            value = value * 10 + (b - '0'.code.toByte())
        }
        i++
    }
    return if (negative) -value else value
}

/**
 * Parse a signed base-10 integer from an absolute byte range: an optional leading
 * '-' followed by digits. No validation of non-digit bytes is performed; the caller
 * is expected to pass a range that contains a well-formed integer.
 *
 * @param offset absolute index of the first byte
 * @param length number of bytes in the field
 * @param getByte absolute byte accessor
 */
internal inline fun parseSignedLong(
    offset: Int,
    length: Int,
    getByte: (Int) -> Byte,
): Long {
    var i = offset
    val end = offset + length
    var negative = false
    if (length > 0 && getByte(i) == '-'.code.toByte()) {
        negative = true
        i++
    }
    var value = 0L
    while (i < end) {
        value = value * 10 + (getByte(i) - '0'.code.toByte())
        i++
    }
    return if (negative) -value else value
}
