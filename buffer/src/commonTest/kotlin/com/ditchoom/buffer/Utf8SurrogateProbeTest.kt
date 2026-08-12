// TEMPORARY PROBE — not for merge. Reports per-platform writeString/utf8Length behavior
// for surrogate inputs by failing with a report string (reliable output on every target).
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test

class Utf8SurrogateProbeTest {
    private val cases =
        listOf(
            "loneHigh(\\uD800)" to "\uD800",
            "loneLow(\\uDC00)" to "\uDC00",
            "highThenEuro(\\uD800€)" to "\uD800€",
            "highThenA(\\uD800A)" to "\uD800A",
            "aThenHigh(A\\uD800)" to "A\uD800",
            "emojiPair(😀)" to "😀",
        )

    private fun probeWrite(
        factory: BufferFactory,
        text: String,
    ): String =
        try {
            factory.allocate(64).use { buf ->
                try {
                    buf.writeString(text, Charset.UTF8)
                    "pos=${buf.position()}"
                } catch (t: Throwable) {
                    "throws=${t::class.simpleName}"
                }
            }
        } catch (t: Throwable) {
            "allocOrFree-throws=${t::class.simpleName}"
        }

    @Test
    fun probe() {
        val sb = StringBuilder("\n=== UTF8-PROBE ===\n")
        for ((name, text) in cases) {
            sb
                .append(name)
                .append(" utf8Length=")
                .append(text.utf8Length())
                .append(" default[")
                .append(probeWrite(BufferFactory.Default, text))
                .append(']')
                .append(" managed[")
                .append(probeWrite(BufferFactory.managed(), text))
                .append(']')
                .append('\n')
        }
        sb.append("=== END PROBE ===")
        throw AssertionError(sb.toString())
    }
}
