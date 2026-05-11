package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Phase 0.4 — informational characterization of `readByteArray(size)`'s
 * per-platform aliasing behaviour. Records the documented platform reality
 * so the rewritten kdoc (Change 4) can quote it, and so a future change
 * that flips the aliasing behaviour on any platform fails loudly.
 *
 * Does NOT gate the buffer-codec lockdown design. Consumers who need
 * independence on every platform use `copyToByteArray(size)` (Change 3 —
 * pinned by `CopyToByteArrayIndependenceTests`). `readByteArray` retains
 * its existing, platform-dependent semantics with an honest kdoc.
 *
 * Verified source state (2026-05-11):
 *   - JVM desktop / Android: copies (System.arraycopy / ByteBuffer.get into fresh ByteArray)
 *   - Apple (NSDataBuffer / MutableDataBuffer): copies (`bytePointer.readBytes(size)`)
 *   - Linux native (NativeBuffer): copies (UnsafeMemory.copyMemoryToArray)
 *   - WASM (LinearBuffer): copies (byte-by-byte from linear memory into Wasm-GC heap)
 *   - non-JVM heap (ByteArrayBuffer): copies (data.copyOfRange)
 *   - JS (JsBuffer): aliases (`Int8Array(subArray.buffer, …).unsafeCast<ByteArray>()`)
 */
internal expect val readByteArrayAliasesSource: Boolean

class ReadByteArrayCharacterizationTests {
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
    fun readByteArrayBehavesPerPlatformContract() {
        for ((name, factory) in factories()) {
            for (size in sizes) {
                val original = originalBytes(size)
                val mutated = mutatedBytes(size)

                val src = factory.allocate(size)
                src.writeBytes(original)
                src.resetForRead()

                val returned = src.readByteArray(size)
                // Mutate src after readByteArray returned. If the returned array
                // shared storage with src, it now reflects the mutation.
                src.position(0)
                src.setLimit(src.capacity)
                src.writeBytes(mutated)
                src.resetForRead()

                val expected = if (readByteArrayAliasesSource) mutated else original
                assertContentEquals(
                    expected,
                    returned,
                    "factory=$name, size=$size: readByteArray expected to ${
                        if (readByteArrayAliasesSource) "alias source" else "copy independently of source"
                    } on this platform",
                )
            }
        }
    }
}
