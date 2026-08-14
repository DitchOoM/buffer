package com.ditchoom.buffer

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **`Utf8.Lenient` must write U+FFFD for an unpaired surrogate wherever it appears — including last.**
 *
 * `BaseJvmBuffer.writeUtf8Substituting` has two halves: a native fast path
 * (`tryWriteUtf8LenientToNative`, overridden by `DirectJvmBuffer` and the two FFM buffers) and a
 * `CharsetEncoder` fallback. `BufferFactory.Default` hands out a direct/FFM buffer on the JVM, so every
 * pre-existing fixture takes the override and the fallback had no coverage on this platform at all.
 *
 * It is not a rare path elsewhere. **Nothing on Android overrides it** — the override lives in `jvmMain`
 * and `jvm21Main`, neither of which Android compiles — so every Android write takes the fallback, as
 * does a heap buffer here and a JVM < 21 without `--add-opens`.
 *
 * The fallback called `encode(in, out, endOfInput = true)` and never `flush`. `CharsetEncoder` requires
 * the flush, and the platforms disagree about how much it is still owed: a **trailing** unpaired
 * surrogate leaves the encoder holding the pending half, which the JDK emits during `encode` and
 * Android's ICU-backed encoder emits only on `flush`. Measured on ART (API 33), one lone surrogate with
 * the U+FFFD replacement configured:
 *
 *     encode -> UNDERFLOW, wrote 0, input fully consumed
 *     flush  -> UNDERFLOW, wrote 3
 *
 * So `Utf8.Lenient` — documented "identical bytes on every platform" — wrote **nothing** on Android for
 * any text ending in an unpaired surrogate, while `utf8Size()` still counted the three bytes it
 * guarantees to match. A surrogate anywhere but last was already correct, because the following char
 * forces the encoder to resolve it during `encode`; that asymmetry is why the corpus missed it.
 *
 * `com.ditchoom:webrtc` caught it downstream: it sizes an SCTP send buffer from `utf8Size()` and writes
 * with `Utf8.Lenient`, so a text message ending in an unpaired surrogate went out **empty** on Android
 * while the RFC 8841 send gate had already charged it three bytes.
 *
 * **What this fixture can and cannot prove.** It pins the heap path against the library's own reference
 * encoder on the JVM, which is worth having — that path had none. It does **not** gate the Android
 * regression: the JDK's encoder emits the replacement during `encode`, so these assertions pass with and
 * without the `flush`. Only a device lane can gate it, and buffer has no Android instrumented tests at
 * all (no `withDeviceTest` / `sourceSetTreeName` in any build script), which is exactly why this shipped.
 * Do not read a green run here as proof the ART behaviour is fixed.
 */
class HeapLenientSubstitutionTest {
    private fun heap(capacity: Int = 64) = HeapJvmBuffer(ByteBuffer.allocate(capacity))

    private fun bytesWritten(text: String): Int {
        val buffer = heap()
        buffer.writeText(text, Utf8.Lenient)
        return buffer.position()
    }

    /**
     * Surrogates are constructed rather than written as literals — the same discipline the rest of the
     * suite adopted in #354, so a compiler that rewrites an unpaired surrogate in a literal cannot
     * quietly turn these into ASCII cases.
     */
    private val hi = Char(0xD83D).toString()
    private val lo = Char(0xDE00).toString()

    @Test
    fun heap_path_writes_the_same_bytes_as_the_reference_encoder() {
        val corpus =
            listOf(
                // Trailing ill-formed input first: this is the shape the platforms disagreed about.
                hi to "an unpaired high surrogate, alone (trailing)",
                lo to "an unpaired low surrogate, alone (trailing)",
                "a$hi" to "ASCII then a trailing unpaired high surrogate",
                "😀$lo" to "a real pair then a trailing unpaired low surrogate",
                lo + hi to "a low surrogate then a trailing high one",
                // Interior ill-formed input, which was correct even before the flush.
                "a${hi}b" to "an unpaired high surrogate between ASCII",
                "$hi😀" to "an unpaired high surrogate before a real pair",
                // Well-formed control cases.
                "ping" to "plain ASCII",
                "héllo wörld" to "mixed one- and two-byte",
                "日本語" to "three-byte characters",
                "😀" to "a well-formed pair",
                "" to "the empty string",
            )
        for ((text, what) in corpus) {
            assertEquals(
                Utf8TextEncoder.encodeSubstituting(text).size,
                bytesWritten(text),
                "heap writeText disagrees with the reference encoder for $what",
            )
        }
    }

    /** The guarantee callers size allocations from, stated directly on the path that broke it. */
    @Test
    fun utf8Size_equals_what_the_heap_path_writes() {
        for (text in listOf(hi, lo, "a$hi", "😀$lo", "a${hi}b", "ping", "😀", "日本語")) {
            assertEquals(text.utf8Size(), bytesWritten(text), "utf8Size disagrees with the heap write")
        }
    }

    /** Absolute, so a change that moved both the encoder and the fallback together could not hide. */
    @Test
    fun a_trailing_unpaired_surrogate_costs_three_bytes() {
        assertEquals(3, bytesWritten(hi), "U+FFFD is three UTF-8 bytes")
        assertEquals(4, bytesWritten("a$hi"), "one ASCII byte plus U+FFFD's three")
    }
}
