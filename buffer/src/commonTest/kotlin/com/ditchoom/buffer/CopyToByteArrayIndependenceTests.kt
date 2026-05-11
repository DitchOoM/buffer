package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Phase 0.1 — load-bearing contract for `ReadBuffer.copyToByteArray(size)`.
 *
 * The buffer-codec lockdown design (Change 3) relies on `copyToByteArray`
 * returning an independently-allocated ByteArray on every platform — bytes
 * the consumer is safe to retain past the buffer's scope. If any platform
 * override breaks the independence contract (e.g. ports the JS alias trick
 * for speed), this test catches it before release.
 *
 * Method:
 *   1. Allocate src; fill with originalBytes; resetForRead.
 *   2. copied = src.copyToByteArray(size).
 *   3. Overwrite src's contents with mutatedBytes.
 *   4. Assert `copied` still matches originalBytes (no aliasing).
 *
 * Runs across every (factory × size) combination Phase 0 cares about.
 */
class CopyToByteArrayIndependenceTests {
    private val sizes = intArrayOf(8, 256, 16 * 1024, 1 * 1024 * 1024)

    private fun factories(): List<Pair<String, BufferFactory>> {
        val list =
            mutableListOf(
                "Default" to BufferFactory.Default,
                "managed" to BufferFactory.managed(),
            )
        if (isDeterministicAllocateSupported) {
            list += "deterministic" to BufferFactory.deterministic()
        }
        return list
    }

    private fun originalBytes(size: Int): ByteArray = ByteArray(size) { (it and 0x7F).toByte() }

    private fun mutatedBytes(size: Int): ByteArray = ByteArray(size) { ((it and 0x7F) xor 0x55).toByte() }

    @Test
    fun copyToByteArrayDoesNotAliasSource() {
        for ((name, factory) in factories()) {
            for (size in sizes) {
                val original = originalBytes(size)
                val mutated = mutatedBytes(size)

                val src = factory.allocate(size)
                src.writeBytes(original)
                src.resetForRead()

                val copied = src.copyToByteArray(size)

                // After copyToByteArray, src.position == size (drained).
                // Mutate src in place; if copied aliased src, copied would see the change.
                src.position(0)
                src.setLimit(src.capacity)
                src.writeBytes(mutated)
                src.resetForRead()

                assertContentEquals(
                    original,
                    copied,
                    "factory=$name, size=$size: copyToByteArray must copy, not alias source",
                )
            }
        }
    }
}
