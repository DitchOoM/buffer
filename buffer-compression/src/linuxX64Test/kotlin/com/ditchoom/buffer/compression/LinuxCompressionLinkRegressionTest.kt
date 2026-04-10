package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for K/N AutoboxingTransformer crash (LINUX_NATIVE_COMPILER_BUG.md).
 *
 * The bug was a link-time crash in `linkDebugTestLinuxX64` caused by the `inline`
 * modifier on `withInputPointer` — a generic inline function calling another inline
 * function (`usePinned`) that also returns the generic type R.
 *
 * **Why this test catches the regression:** The bug is a link-time crash — if
 * `linuxX64Test` runs at all, the link succeeded and the bug is not present.
 * The compress/decompress round-trip additionally exercises both `withInputPointer`
 * call sites (`R=Unit` for compress, `R=Int` for decompress) to confirm runtime
 * correctness.
 */
class LinuxCompressionLinkRegressionTest {
    @Test
    fun compressDecompressRoundTrip() {
        val factory = BufferFactory.Default
        val original = factory.allocate(64)
        repeat(64) { original.writeByte((it % 256).toByte()) }
        original.resetForRead()

        // Exercise compress path (R=Unit in withInputPointer)
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Deflate, bufferFactory = factory)
        val compressed = mutableListOf<ReadBuffer>()
        compressor.compressUnsafe(original) { compressed.add(it) }
        compressor.finishUnsafe { compressed.add(it) }
        compressor.close()

        // Exercise decompress path (R=Int in withInputPointer)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Deflate, bufferFactory = factory)
        val decompressed = mutableListOf<ReadBuffer>()
        for (chunk in compressed) {
            decompressor.decompressUnsafe(chunk) { decompressed.add(it) }
        }
        decompressor.finishUnsafe { decompressed.add(it) }
        decompressor.close()

        // Verify round-trip
        val totalBytes = decompressed.sumOf { it.remaining() }
        assertEquals(64, totalBytes)
    }
}
