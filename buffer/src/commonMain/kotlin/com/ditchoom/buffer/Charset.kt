package com.ditchoom.buffer

// The literals below are the average/maximum bytes-per-character ratios that define each encoding
// (e.g. UTF-8 averages ~1.1 bytes/char, up to 4); they are intrinsic charset data, not tunable values.
@Suppress("MagicNumber")
enum class Charset(
    val averageBytesPerChar: Float,
    val maxBytesPerChar: Float,
) {
    UTF8(1.1f, 4f),
    UTF16(2f, 4f),
    UTF16BigEndian(2f, 4f),
    UTF16LittleEndian(2f, 4f),
    ASCII(1f, 1f),
    ISOLatin1(1f, 1f), // aka ISO/IEC 8859-1
    UTF32(4f, 4f),
    UTF32LittleEndian(4f, 4f),
    UTF32BigEndian(4f, 4f),
}
