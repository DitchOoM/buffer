// TEMPORARY PROBE — not for merge. Reports per-platform readString behavior for malformed
// UTF-8 bytes by failing with a report string (reliable output on every target).
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test

class ReadStringMalformedProbeTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private val cases =
        listOf(
            "valid(A€)" to bytes(0x41, 0xE2, 0x82, 0xAC),
            "truncated2AtEnd(C3)" to bytes(0xC3),
            "truncated3AtEnd(E2 82)" to bytes(0xE2, 0x82),
            "truncated4AtEnd(F0 9F 98)" to bytes(0xF0, 0x9F, 0x98),
            "bareContinuation(80)" to bytes(0x80),
            "twoBareContinuations(80 80)" to bytes(0x80, 0x80),
            "overlongSlash(C0 AF)" to bytes(0xC0, 0xAF),
            "encodedSurrogate(ED A0 80)" to bytes(0xED, 0xA0, 0x80),
            "outOfRange(F4 90 80 80)" to bytes(0xF4, 0x90, 0x80, 0x80),
            "truncated3Mid(41 E2 82 42)" to bytes(0x41, 0xE2, 0x82, 0x42),
            "lead2ThenAscii(C3 41)" to bytes(0xC3, 0x41),
        )

    private fun codePoints(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val cp =
                if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    val v = 0x10000 + ((ch.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                    i++
                    v
                } else {
                    ch.code
                }
            sb.append("U+").append(cp.toString(16).uppercase()).append(' ')
            i++
        }
        return sb.toString().trim()
    }

    private fun probeRead(
        factory: BufferFactory,
        data: ByteArray,
    ): String =
        try {
            factory.allocate(data.size + 8).use { buf ->
                buf.writeBytes(data)
                buf.resetForRead()
                try {
                    val s = buf.readString(data.size, Charset.UTF8)
                    "[${codePoints(s)}] pos=${buf.position()}"
                } catch (t: Throwable) {
                    "throws=${t::class.simpleName} pos=${buf.position()}"
                }
            }
        } catch (t: Throwable) {
            "allocOrFree-throws=${t::class.simpleName}"
        }

    @Test
    fun probe() {
        val sb = StringBuilder("\n=== READSTRING-PROBE ===\n")
        for ((name, data) in cases) {
            sb
                .append(name)
                .append(" default{")
                .append(probeRead(BufferFactory.Default, data))
                .append('}')
                .append(" managed{")
                .append(probeRead(BufferFactory.managed(), data))
                .append('}')
                .append(" deterministic{")
                .append(probeRead(BufferFactory.deterministic(), data))
                .append('}')
                .append('\n')
        }
        sb.append("=== END PROBE ===")
        throw AssertionError(sb.toString())
    }
}
