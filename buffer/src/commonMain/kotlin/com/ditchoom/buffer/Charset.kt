package com.ditchoom.buffer

// The literals below are the average/maximum bytes-per-character ratios that define each encoding
// (e.g. UTF-8 averages ~1.1 bytes/char, up to 3); they are intrinsic charset data, not tunable values.
//
// Maximums are per UTF-16 CHAR, not per code point, matching the JDK's CharsetEncoder values:
// - UTF-8: 3 (a surrogate pair is 4 bytes over 2 chars = 2/char; an unpaired surrogate under
//   U+FFFD substitution is 3; a BMP char is at most 3)
// - UTF-16BE/LE: 2 (every char is exactly one code unit)
// - UTF-16 (BOM-prefixed): 4 (the JDK charges the 2-byte BOM against the first char)
@Suppress("MagicNumber")
enum class Charset(
    val averageBytesPerChar: Float,
    val maxBytesPerChar: Float,
) {
    UTF8(1.1f, 3f),
    UTF16(2f, 4f),
    UTF16BigEndian(2f, 2f),
    UTF16LittleEndian(2f, 2f),
    ASCII(1f, 1f),
    ISOLatin1(1f, 1f), // aka ISO/IEC 8859-1
    UTF32(4f, 4f),
    UTF32LittleEndian(4f, 4f),
    UTF32BigEndian(4f, 4f),
}
