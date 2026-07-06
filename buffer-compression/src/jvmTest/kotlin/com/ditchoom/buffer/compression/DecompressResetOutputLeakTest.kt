package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the permessage-deflate output-buffer leak on `reset()` (JVM/Android).
 *
 * A [StreamingDecompressor] allocates an output buffer *before* it knows whether the codec
 * will produce bytes. When a message decompresses to an exact multiple of the output buffer
 * size, the SYNC_FLUSH marker (`00 00 FF FF`) — fed as a *separate* decompress call by the
 * WebSocket codec — is processed into a freshly allocated buffer that yields **zero** bytes.
 * `flush()` correctly declines to emit that empty buffer, but `reset()`/`close()` used to null
 * the field without releasing it — leaking one output-sized allocation per no-context-takeover
 * message. Under `deterministic()` / `DirectByteBuffer` that exhausted ~1 GiB of direct memory
 * across an Autobahn category-12 run; GC-managed factories masked it.
 *
 * This is the JVM streaming-codec model (a reused `currentOutput` field); the native impls use
 * allocate-per-emit and already release on reset. Using a
 * [com.ditchoom.buffer.pool.BufferPool] as the factory turns the leak into a deterministic
 * assertion: a buffer dropped instead of released never returns to the pool, so the pool must
 * allocate a fresh one every cycle (`poolMisses` ≈ cycles). With the fix the marker buffer is
 * released and reused, so misses stay tiny.
 */
class DecompressResetOutputLeakTest {
    @Test
    fun decompressorReleasesEmptyMarkerBufferOnReset() {
        if (!supportsSyncCompression) return

        // Payload decompresses to exactly one 32 KB output buffer, so the trailing sync-flush
        // marker lands in a fresh, empty output buffer — the leak trigger.
        val outputBufferSize = 32768
        val raw = BufferFactory.Default.allocate(outputBufferSize)
        val rng = kotlin.random.Random(0x12_2b)
        repeat(outputBufferSize) { raw.writeByte(rng.nextInt().toByte()) }
        raw.resetForRead()

        // Raw deflate + SYNC_FLUSH (compress + flush, no finish) — the permessage-deflate wire
        // shape. The resulting block ends in the 00 00 FF FF marker.
        val compressedChunks = mutableListOf<ReadBuffer>()
        val compressor =
            StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw, bufferFactory = BufferFactory.Default)
        compressor.compress(raw) { compressedChunks.add(it) }
        compressor.flush { compressedChunks.add(it) }
        val fullLen = compressedChunks.sumOf { it.remaining() }
        val full = BufferFactory.Default.allocate(fullLen)
        for (c in compressedChunks) full.write(c)
        full.resetForRead()
        compressor.close()

        // Split off the trailing 00 00 FF FF sync-flush marker: the codec transmits the deflate
        // block without it and feeds the marker as a SEPARATE decompress call (see
        // WebSocketCodec.decompressToBufferSync). That separate call is what forces a fresh, empty
        // output buffer to be allocated for the marker when the data ended on a buffer boundary.
        val dataLen = fullLen - 4
        val data = BufferFactory.Default.allocate(dataLen)
        full.position(0)
        full.setLimit(dataLen)
        data.write(full)
        data.resetForRead()

        withPool(defaultBufferSize = outputBufferSize, maxPoolSize = 8) { pool ->
            val decompressor =
                StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw, bufferFactory = pool)
            val cycles = 50
            repeat(cycles) {
                data.position(0)
                val marker = BufferFactory.Default.allocate(4)
                marker.writeByte(0x00)
                marker.writeByte(0x00)
                marker.writeByte(0xFF.toByte())
                marker.writeByte(0xFF.toByte())
                marker.resetForRead()
                val out = mutableListOf<ReadBuffer>()
                decompressor.decompress(data) { out.add(it) }
                decompressor.decompress(marker) { out.add(it) }
                decompressor.flush { out.add(it) }
                decompressor.reset()
                out.freeAll() // return the emitted (data) chunks to the pool
                marker.freeIfNeeded()
            }
            decompressor.close()

            val stats = pool.stats()
            // With the fix, the data buffer and the empty marker buffer are released back to the pool
            // each cycle and reused, so poolMisses stays tiny. The leak dropped the marker buffer on
            // reset(), so the pool had to allocate a fresh one every cycle → poolMisses ≈ cycles.
            assertTrue(
                stats.poolMisses < 10,
                "Pool missed ${stats.poolMisses} times across $cycles decompress+reset cycles ($stats) — " +
                    "the empty sync-flush-marker output buffer is leaking on reset() instead of being released.",
            )
        }
    }
}
