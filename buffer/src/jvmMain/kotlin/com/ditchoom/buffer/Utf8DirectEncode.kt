// UTF-8 is defined in terms of bit patterns (0x80/0xC0/0xE0/0xF0 lead bytes, 0x3F continuation mask,
// 0xD800..0xDFFF surrogates); naming each would obscure the encoding rather than clarify it.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

// SHADOW TWIN: an identical copy lives in src/jvm21Main. The algorithm is JDK-agnostic; only the
// directPutByte it binds to differs (Unsafe here on JVM 8-20, FFM in the jvm21 copy). The multi-
// release JAR loads the jvm21 copy on JDK 21+, exactly like DirectUncheckedAccess.kt. Keep in sync.

import java.nio.charset.MalformedInputException

/**
 * Encodes [text] as UTF-8 straight into native memory at [base] + index, starting at [startPosition]
 * and never writing at or past [limit]. Returns the new position (one-past the last byte written).
 *
 * This is the write-side counterpart to the [directGetByte] read fast path and mirrors the native
 * (simdutf) `writeString`: it walks the UTF-16 [CharSequence] and writes each scalar's 1–4 UTF-8
 * bytes through [directPutByte] — a raw native store with no per-byte `java.nio.Buffer.session()`
 * scope check and no intermediate `char[]`/`byte[]`. On JVM 21+ [directPutByte] is FFM-backed; on
 * 8–20 it is `Unsafe`. The JDK `CharsetEncoder` path this replaces used `encodeBufferLoop`, which
 * does a `DirectByteBuffer.put(byte)` — and thus a session check — for every single byte.
 *
 * Behaviour matches the REPORT-mode `CharsetEncoder` it replaces:
 *  - a lone/unpaired surrogate throws [MalformedInputException] (no substitution), and
 *  - running out of window throws [BufferOverflowException] before writing past [limit].
 * On either throw the destination may hold a partial encoding and the caller's position is unchanged.
 *
 * The one when-branch per UTF-8 sequence length (1-4 bytes) plus the surrogate-pair path drive
 * detekt's CyclomaticComplexMethod count, and the malformed-surrogate + overflow guards drive its
 * ThrowsCount — both intrinsic to a correct single-pass encoder, hence the suppressions.
 */
@Suppress("CyclomaticComplexMethod", "ThrowsCount")
internal fun encodeUtf8ToNative(
    text: CharSequence,
    startPosition: Int,
    limit: Int,
    base: Long,
): Int {
    var pos = startPosition
    val len = text.length
    var i = 0
    while (i < len) {
        val c = text[i].code
        when {
            c < 0x80 -> {
                if (pos >= limit) throw overflow(pos, limit)
                directPutByte(base + pos, c.toByte())
                pos += 1
            }
            c < 0x800 -> {
                if (pos + 2 > limit) throw overflow(pos, limit)
                directPutByte(base + pos, (0xC0 or (c ushr 6)).toByte())
                directPutByte(base + pos + 1, (0x80 or (c and 0x3F)).toByte())
                pos += 2
            }
            c < 0xD800 || c >= 0xE000 -> {
                // BMP scalar (excludes the surrogate range 0xD800..0xDFFF): 3 bytes.
                if (pos + 3 > limit) throw overflow(pos, limit)
                directPutByte(base + pos, (0xE0 or (c ushr 12)).toByte())
                directPutByte(base + pos + 1, (0x80 or ((c ushr 6) and 0x3F)).toByte())
                directPutByte(base + pos + 2, (0x80 or (c and 0x3F)).toByte())
                pos += 3
            }
            c < 0xDC00 -> {
                // High surrogate — must be followed by a low surrogate to form a supplementary scalar.
                if (i + 1 >= len) throw MalformedInputException(1)
                val low = text[i + 1].code
                if (low < 0xDC00 || low >= 0xE000) throw MalformedInputException(1)
                val cp = 0x10000 + ((c - 0xD800) shl 10) + (low - 0xDC00)
                if (pos + 4 > limit) throw overflow(pos, limit)
                directPutByte(base + pos, (0xF0 or (cp ushr 18)).toByte())
                directPutByte(base + pos + 1, (0x80 or ((cp ushr 12) and 0x3F)).toByte())
                directPutByte(base + pos + 2, (0x80 or ((cp ushr 6) and 0x3F)).toByte())
                directPutByte(base + pos + 3, (0x80 or (cp and 0x3F)).toByte())
                pos += 4
                i += 1 // consumed the low surrogate as well
            }
            else -> throw MalformedInputException(1) // lone low surrogate (0xDC00..0xDFFF)
        }
        i += 1
    }
    return pos
}

private fun overflow(
    pos: Int,
    limit: Int,
): BufferOverflowException =
    BufferOverflowException(
        "Buffer overflow: cannot write UTF-8 string, ran out of window at position $pos (limit=$limit)",
    )
