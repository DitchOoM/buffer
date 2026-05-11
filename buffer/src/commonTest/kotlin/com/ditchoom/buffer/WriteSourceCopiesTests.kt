package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Phase 0.2 — pins the contract that `dst.write(source: ReadBuffer)` *copies*
 * bytes from source into dst (rather than aliasing) on every platform.
 *
 * This underwrites canonical decode pattern #2 from the buffer-codec lockdown
 * plan: a consumer that wants to own a `PlatformBuffer` past the codec's
 * scope allocates `dst` via their factory and calls `dst.write(buffer)`. If
 * the bytes aliased the source instead of copying, the consumer would still
 * read reclaimed pool memory.
 *
 * Method:
 *   1. Allocate src; fill with originalBytes; resetForRead.
 *   2. Allocate dst (possibly from a different factory); dst.write(src).
 *   3. Overwrite src's contents with mutatedBytes.
 *   4. Read dst's bytes; assert they match originalBytes (no aliasing).
 *
 * Runs across every (srcFactory × dstFactory × size) combination Phase 0
 * cares about. A platform/backend that ever lets `dst.write(src)` aliase
 * fails this test immediately.
 */
class WriteSourceCopiesTests {
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
    fun writeFromSourceCopiesBytesIntoDst() {
        for ((srcName, srcFactory) in factories()) {
            for ((dstName, dstFactory) in factories()) {
                for (size in sizes) {
                    val original = originalBytes(size)
                    val mutated = mutatedBytes(size)

                    val src = srcFactory.allocate(size)
                    src.writeBytes(original)
                    src.resetForRead()

                    val dst = dstFactory.allocate(size)
                    dst.write(src)
                    dst.resetForRead()

                    // After dst.write(src), src.position == src.limit (drained).
                    // Mutate src in place; if dst aliased src, dst would see the change.
                    src.position(0)
                    src.setLimit(src.capacity)
                    src.writeBytes(mutated)
                    src.resetForRead()

                    val dstBytes = dst.readByteArray(size)
                    assertContentEquals(
                        original,
                        dstBytes,
                        "src=$srcName, dst=$dstName, size=$size: dst.write(src) must copy, not alias",
                    )
                }
            }
        }
    }
}
