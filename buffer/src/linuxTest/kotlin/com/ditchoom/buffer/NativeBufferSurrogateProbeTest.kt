// TEMPORARY PROBE — not for merge. Reports NativeBuffer (simdutf path) surrogate behavior.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test

class NativeBufferSurrogateProbeTest {
    private val cases =
        listOf(
            "loneHigh(\\uD800)" to "\uD800",
            "loneLow(\\uDC00)" to "\uDC00",
            "highThenEuro(\\uD800€)" to "\uD800€",
            "highThenA(\\uD800A)" to "\uD800A",
            "aThenHigh(A\\uD800)" to "A\uD800",
            "emojiPair(😀)" to "😀",
        )

    @Test
    fun probe() {
        val sb = StringBuilder("\n=== NATIVEBUFFER-PROBE ===\n")
        for ((name, text) in cases) {
            val outcome =
                try {
                    NativeBuffer.allocate(64).use { buf ->
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
            sb.append(name).append(' ').append(outcome).append('\n')
        }
        sb.append("=== END PROBE ===")
        throw AssertionError(sb.toString())
    }
}
