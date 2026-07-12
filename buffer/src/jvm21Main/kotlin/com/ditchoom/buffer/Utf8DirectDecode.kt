// UTF-8 is defined in terms of bit patterns (0x80/0xC0/0xE0/0xF0 lead bytes, 0x3F continuation mask,
// 0xD800..0xDFFF surrogates, 0x10FFFF ceiling); naming each would obscure the decoding rather than
// clarify it.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import java.nio.charset.MalformedInputException

// FFM-ONLY (jvm21) — deliberately NOT a shadow twin of a jvmMain copy, unlike Utf8DirectEncode.kt.
// The read fast path pays off only when the per-byte accessor is a JIT intrinsic: here directGetByte
// is the FFM EVERYTHING.get(JAVA_BYTE, addr), which C2 intrinsifies, so this beats the JDK
// CharsetDecoder's decodeBufferLoop by 1.4-2.4x (see ReadStringDecodeBench). On the sun.misc.Unsafe
// path (jvmMain, JVM 8-20 / Android) the equivalent Unsafe.getByte loop measured 2-3x SLOWER than
// decodeBufferLoop, so that path keeps using the CharsetDecoder — there is no jvmMain counterpart.

/**
 * Per-thread scratch [CharArray] that [decodeUtf8FromNative] decodes into, grown on demand and kept
 * for subsequent calls so steady-state reads of similar-sized payloads allocate nothing but the
 * result String. UTF-8 never produces more chars than input bytes (1/2/3-byte scalars → 1 char,
 * 4-byte → 2 chars, i.e. ≤1 char per byte), so a scratch of `byteCount` chars can never overflow.
 */
private val decodeCharScratch =
    object : ThreadLocal<CharArray>() {
        override fun initialValue(): CharArray = CharArray(INITIAL_DECODE_SCRATCH_CHARS)
    }

private const val INITIAL_DECODE_SCRATCH_CHARS = 1024

/**
 * Decodes the UTF-8 bytes in native memory at [base] + [[startPosition], [endPosition]) into a
 * String. Read-side counterpart to [encodeUtf8ToNative]: it pulls each byte through [directGetByte]
 * — a raw native load with no per-byte `java.nio.Buffer.session()` scope check — instead of the JDK
 * `CharsetDecoder`, whose `decodeBufferLoop` (the only loop a *direct* ByteBuffer can take, since it
 * has no backing array) does a `DirectByteBuffer.get()` — and thus a session check — for every byte.
 * On JVM 21+ [directGetByte] is FFM-backed; on 8–20 it is `Unsafe`.
 *
 * Validation is strict and matches the REPORT-mode UTF-8 decoder it replaces: overlong forms
 * (0xC0/0xC1 leads, and E0/F0 leads with a too-small second byte), UTF-16 surrogate scalars encoded
 * as UTF-8 (ED A0..BF ...), code points above 0x10FFFF (F4 90.. and F5..FF leads), lone continuation
 * bytes, and sequences truncated by [endPosition] all throw [MalformedInputException]. On a throw the
 * caller's position is unchanged and the returned scratch holds a partial decode that is discarded.
 *
 * The one when-branch per UTF-8 sequence length (1-4 bytes) plus the continuation/overlong/surrogate
 * guards drive detekt's CyclomaticComplexMethod and ThrowsCount counts — both intrinsic to a correct
 * single-pass strict decoder, hence the suppressions.
 */
@Suppress("CyclomaticComplexMethod", "ThrowsCount", "NestedBlockDepth", "LongMethod")
internal fun decodeUtf8FromNative(
    base: Long,
    startPosition: Int,
    endPosition: Int,
): String {
    val byteCount = endPosition - startPosition
    var chars = decodeCharScratch.get()
    if (chars.size < byteCount) {
        chars = CharArray(byteCount)
        decodeCharScratch.set(chars)
    }
    var ci = 0
    var pos = startPosition
    while (pos < endPosition) {
        val b0 = directGetByte(base + pos).toInt() and 0xFF
        when {
            b0 < 0x80 -> {
                chars[ci++] = b0.toChar()
                pos += 1
            }
            b0 < 0xC2 -> throw malformed() // 0x80..0xBF lone continuation, or 0xC0/0xC1 overlong lead
            b0 < 0xE0 -> {
                // 2-byte scalar 0x80..0x7FF.
                if (pos + 2 > endPosition) throw malformed()
                val b1 = directGetByte(base + pos + 1).toInt() and 0xFF
                if (b1 and 0xC0 != 0x80) throw malformed()
                chars[ci++] = (((b0 and 0x1F) shl 6) or (b1 and 0x3F)).toChar()
                pos += 2
            }
            b0 < 0xF0 -> {
                // 3-byte scalar 0x800..0xFFFF, excluding the surrogate range 0xD800..0xDFFF.
                if (pos + 3 > endPosition) throw malformed()
                val b1 = directGetByte(base + pos + 1).toInt() and 0xFF
                val b2 = directGetByte(base + pos + 2).toInt() and 0xFF
                if (b1 and 0xC0 != 0x80 || b2 and 0xC0 != 0x80) throw malformed()
                if (b0 == 0xE0 && b1 < 0xA0) throw malformed() // overlong (would fit in 2 bytes)
                if (b0 == 0xED && b1 >= 0xA0) throw malformed() // encoded UTF-16 surrogate
                chars[ci++] = (((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)).toChar()
                pos += 3
            }
            b0 < 0xF5 -> {
                // 4-byte scalar 0x10000..0x10FFFF → a UTF-16 surrogate pair.
                if (pos + 4 > endPosition) throw malformed()
                val b1 = directGetByte(base + pos + 1).toInt() and 0xFF
                val b2 = directGetByte(base + pos + 2).toInt() and 0xFF
                val b3 = directGetByte(base + pos + 3).toInt() and 0xFF
                if (b1 and 0xC0 != 0x80 || b2 and 0xC0 != 0x80 || b3 and 0xC0 != 0x80) throw malformed()
                if (b0 == 0xF0 && b1 < 0x90) throw malformed() // overlong (would fit in 3 bytes)
                if (b0 == 0xF4 && b1 >= 0x90) throw malformed() // above 0x10FFFF
                val cp = ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                val u = cp - 0x10000
                chars[ci++] = (0xD800 or (u ushr 10)).toChar()
                chars[ci++] = (0xDC00 or (u and 0x3FF)).toChar()
                pos += 4
            }
            else -> throw malformed() // 0xF5..0xFF: no lead byte encodes a valid scalar
        }
    }
    return String(chars, 0, ci)
}

// REPORT-mode decoders report the malformed *length* (the maximal subpart), but since we throw on
// first error the exact length is immaterial — 1 keeps the exception shape identical to the encoder's.
private fun malformed(): MalformedInputException = MalformedInputException(1)
