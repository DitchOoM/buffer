package com.ditchoom.buffer

/**
 * Verification harness for [TextPolicy.custom] authors — run it in your `commonTest` against
 * every platform you ship, with samples representative of your data (include ill-formed input
 * if your transcoder accepts it).
 *
 * Built-in policies are vector-tested and platform-accelerated by the library; a custom
 * transcoder's contract is its author's responsibility. This kit checks the invariants the
 * library and its callers rely on:
 *
 * 1. `size(text)` equals the bytes `encodeInto` writes — for every sample. (The default
 *    derived [TextEncoder.size] cannot violate this; an overridden one can.)
 * 2. Encoding is deterministic: the same sample always produces identical bytes.
 * 3. `decodeFrom(buffer, n)` consumes exactly `n` bytes when it returns.
 * 4. Fluent write results identify the written buffer (wrapper transparency holds).
 *
 * Violations throw [AssertionError] naming the sample and the broken invariant.
 */
object TextTranscoderContractKit {
    fun verify(
        policy: FluentTextPolicy,
        samples: List<CharSequence>,
        factory: BufferFactory = BufferFactory.managed(),
    ) {
        require(samples.isNotEmpty()) { "provide at least one sample" }
        for ((index, sample) in samples.withIndex()) {
            val declaredSize = policy.size(sample)
            val first = encodeOnce(policy, factory, sample, index, declaredSize)
            val second = encodeOnce(policy, factory, sample, index, declaredSize)
            if (!first.contentEquals(second)) {
                throw AssertionError(
                    "sample[$index]: encode is not deterministic — two runs produced different bytes",
                )
            }
            decodeOnce(policy, factory, index, first)
        }
    }

    private fun encodeOnce(
        policy: FluentTextPolicy,
        factory: BufferFactory,
        sample: CharSequence,
        index: Int,
        declaredSize: Int,
    ): ByteArray =
        factory.allocate(declaredSize + SLACK).use { buffer ->
            val returned = buffer.writeText(sample, policy)
            if (returned !== buffer) {
                throw AssertionError("sample[$index]: fluent result is not the written buffer")
            }
            val written = buffer.position()
            if (written != declaredSize) {
                throw AssertionError(
                    "sample[$index]: size(text) declared $declaredSize byte(s) but encodeInto " +
                        "wrote $written — the size/write invariant is broken",
                )
            }
            buffer.resetForRead()
            buffer.copyToByteArray(written)
        }

    private fun decodeOnce(
        policy: FluentTextPolicy,
        factory: BufferFactory,
        index: Int,
        encoded: ByteArray,
    ) {
        factory.allocate(encoded.size + SLACK).use { buffer ->
            buffer.writeBytes(encoded)
            buffer.resetForRead()
            buffer.readText(encoded.size, policy)
            if (buffer.position() != encoded.size) {
                throw AssertionError(
                    "sample[$index]: decodeFrom consumed ${buffer.position()} byte(s) of " +
                        "${encoded.size} — decoders must consume exactly the requested length",
                )
            }
        }
    }

    /** Headroom so an over-writing encode overflows the size check, not the buffer. */
    private const val SLACK = 16
}
