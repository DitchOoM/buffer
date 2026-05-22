package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Phase 0.3 — pins the contract that `slice()` and `readBytes(n)` are
 * zero-copy views sharing storage with the source buffer on every platform.
 *
 * Informational on its own — `slice()`/`readBytes()` aliasing is a designed
 * property, not a bug. The test exists so that a future "optimization" that
 * silently copies (defeating the zero-copy intent inside codecs that
 * legitimately stage data via slice) fails loudly. It also documents the
 * boundary: aliasing is fine *inside* a codec's decode pass, but the
 * returned value must not transitively carry the alias past the decode
 * scope — see canonical decode pattern docs and the strict-Payload
 * processor rule (Change 1) for the structural closure.
 *
 * Method:
 *   1. Allocate src; fill with originalBytes; resetForRead.
 *   2. Take slice = src.slice() (or readBytes).
 *   3. Overwrite src's contents with mutatedBytes.
 *   4. Read slice's bytes; assert they match mutatedBytes — proving the
 *      slice sees through to src's current state (aliasing).
 */
class SliceAliasingTests {
    private val sizes = intArrayOf(8, 256, 16 * 1024)

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
    fun sliceSharesUnderlyingStorageWithSource() {
        for ((name, factory) in factories()) {
            for (size in sizes) {
                val original = originalBytes(size)
                val mutated = mutatedBytes(size)

                val src = factory.allocate(size)
                src.writeBytes(original)
                src.resetForRead()

                val slice = src.slice()

                // Overwrite src in place.
                src.position(0)
                src.setLimit(src.capacity)
                src.writeBytes(mutated)
                src.resetForRead()

                val sliceBytes = slice.readByteArray(slice.remaining())
                assertContentEquals(
                    mutated,
                    sliceBytes,
                    "factory=$name, size=$size: slice() must alias source (sees post-mutation bytes)",
                )
            }
        }
    }

    @Test
    fun readBytesSharesUnderlyingStorageWithSource() {
        for ((name, factory) in factories()) {
            for (size in sizes) {
                val original = originalBytes(size)
                val mutated = mutatedBytes(size)

                val src = factory.allocate(size)
                src.writeBytes(original)
                src.resetForRead()

                val view = src.readBytes(size)
                // src position is now at limit after readBytes; reset to overwrite.
                src.position(0)
                src.setLimit(src.capacity)
                src.writeBytes(mutated)
                src.resetForRead()

                val viewBytes = view.readByteArray(view.remaining())
                assertContentEquals(
                    mutated,
                    viewBytes,
                    "factory=$name, size=$size: readBytes() must alias source (sees post-mutation bytes)",
                )
            }
        }
    }
}
