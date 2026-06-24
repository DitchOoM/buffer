package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-platform contract test for [WriteBuffer.writeString] / [ReadBuffer.readString].
 *
 * The [Charset] enum exposes nine values, but per-platform support is uneven:
 * the JS/WasmJs backends encode UTF-8 only and decode a subset, while JVM /
 * Apple / Linux cover the full set. The common signature (`charset: Charset =
 * Charset.UTF8`) hides that asymmetry, so this test pins the invariant a
 * caller can actually rely on everywhere:
 *
 *  - a charset either completes the round-trip correctly, or fails loudly with
 *    [UnsupportedOperationException] — it must never silently corrupt bytes;
 *  - UTF-8 works on every platform (it is the default and the wire baseline).
 *
 * Runs on every target via `commonTest`, so a backend that quietly mis-encodes
 * an unsupported charset — instead of rejecting it — fails here rather than in
 * a downstream protocol codec.
 */
class StringCharsetParityTest {
    // Pure ASCII: representable in every charset, so any decode mismatch is a
    // real bug, not a code-point-coverage artifact.
    private val sample = "Hello123"

    private fun factories(): List<Pair<String, BufferFactory>> =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
        )

    @Test
    fun everyCharsetRoundTripsOrThrowsUnsupported() {
        for ((factoryName, factory) in factories()) {
            for (charset in Charset.entries) {
                assertCharsetRoundTripsOrSkips(factoryName, factory, charset)
            }
        }
    }

    private fun assertCharsetRoundTripsOrSkips(
        factoryName: String,
        factory: BufferFactory,
        charset: Charset,
    ) {
        val label = "$factoryName/$charset"
        val buffer = factory.allocate(256)

        val bytesWritten =
            try {
                buffer.writeString(sample, charset)
                buffer.position()
            } catch (_: UnsupportedOperationException) {
                // Acceptable: platform openly rejects writing this charset.
                return
            }

        assertTrue(bytesWritten > 0, "writeString($label) wrote zero bytes for a non-empty string")
        buffer.resetForRead()

        val readBack =
            try {
                buffer.readString(bytesWritten, charset)
            } catch (_: UnsupportedOperationException) {
                // Acceptable: write supported, read not — asymmetric but loud.
                return
            }

        assertEquals(sample, readBack, "round-trip corrupted text for $label")
    }

    @Test
    fun utf8RoundTripsOnEveryPlatform() {
        for ((factoryName, factory) in factories()) {
            val buffer = factory.allocate(256)
            try {
                buffer.writeString(sample, Charset.UTF8)
            } catch (e: UnsupportedOperationException) {
                fail("UTF-8 writeString must be supported on every platform ($factoryName): ${e.message}")
            }
            val bytesWritten = buffer.position()
            buffer.resetForRead()
            assertEquals(
                sample,
                buffer.readString(bytesWritten, Charset.UTF8),
                "UTF-8 round-trip failed ($factoryName)",
            )
        }
    }
}
