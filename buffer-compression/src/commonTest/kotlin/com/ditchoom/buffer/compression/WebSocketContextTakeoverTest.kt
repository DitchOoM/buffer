package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reproduces the exact compress → strip marker → decompress → flush cycle
 * that WebSocket permessage-deflate uses with context takeover.
 *
 * With context takeover, compressor and decompressor are NOT reset between
 * messages. The LZ77 sliding window is preserved, so later messages compress
 * smaller via back-references to earlier messages.
 *
 * PASSES on JVM and Linux. FAILS on JS Node — the second message returns
 * both messages concatenated because the JS decompressor leaks output
 * from the previous `processSyncPersistent` call.
 *
 * Bug: `JsNodeStreamingDecompressor.flush()` calls `processSyncPersistent()`
 * which reads from `stream._outBuffer`. On the second call, stale data from
 * the first decompression is re-emitted.
 */
class WebSocketContextTakeoverTest {
    private val factory = BufferFactory.managed()
    private val allocator = BufferAllocator.Default
    private val syncFlushMarker = factory.wrap(byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))

    private fun stringToBuffer(s: String): ReadBuffer {
        val buf = factory.allocate(s.length * 3)
        buf.writeString(s, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    @Test
    fun twoMessagesWithContextTakeover() {
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                allocator,
            )
        val decompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                allocator,
            )
        // Reuse decoder across messages (same as websocket's DefaultWebSocketClient)
        val sharedDecoder = StreamingStringDecoder()

        val msg1 = """{"msg":"hello"}"""
        val msg2 = """{"msg":"world"}"""

        val result1 = compressAndDecompress(compressor, decompressor, msg1, sharedDecoder)
        assertEquals(msg1, result1, "Message 1 round-trip failed")

        val result2 = compressAndDecompress(compressor, decompressor, msg2, sharedDecoder)
        assertEquals(
            msg2,
            result2,
            "Message 2 round-trip failed: expected='$msg2' actual='$result2' " +
                "(if actual='$msg1$msg2' then decompressor leaked msg1 output)",
        )

        compressor.close()
        decompressor.close()
    }

    @Test
    fun twoMessagesWithContextTakeover_windowBits9() {
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                allocator,
                windowBits = -9,
            )
        val decompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                allocator,
            )

        val msg1 = """{"msg":"hello"}"""
        val msg2 = """{"msg":"world"}"""

        val result1 = compressAndDecompress(compressor, decompressor, msg1)
        assertEquals(msg1, result1)

        val result2 = compressAndDecompress(compressor, decompressor, msg2)
        assertEquals(msg2, result2, "expected='$msg2' actual='$result2'")

        compressor.close()
        decompressor.close()
    }

    @Test
    fun tenMessagesWithContextTakeover() {
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                allocator,
            )
        val decompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                allocator,
            )

        repeat(10) { i ->
            val msg = """{"index":$i,"data":"test payload $i"}"""
            val result = compressAndDecompress(compressor, decompressor, msg)
            assertEquals(msg, result, "Message $i round-trip failed: actual='${result.take(100)}'")
        }

        compressor.close()
        decompressor.close()
    }

    private fun compressAndDecompress(
        compressor: StreamingCompressor,
        decompressor: StreamingDecompressor,
        input: String,
        reusedDecoder: StreamingStringDecoder? = null,
    ): String {
        // Step 1: Compress (same as websocket's compressSync)
        val compressedChunks = mutableListOf<ReadBuffer>()
        compressor.compress(stringToBuffer(input)) { compressedChunks.add(it) }
        compressor.flush { compressedChunks.add(it) }

        // Strip sync flush marker from end (permessage-deflate requirement)
        val combined = combineAll(compressedChunks)
        val stripped = stripSyncFlushMarker(combined)

        // Step 2: Decompress (same as websocket's decompressToStringSync)
        val sb = StringBuilder()
        val decoder = reusedDecoder ?: StreamingStringDecoder()

        // Match websocket's decodeAndFree exactly: decode then free
        fun decodeAndFree(chunk: ReadBuffer) {
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }

        decompressor.decompress(stripped) { decodeAndFree(it) }
        syncFlushMarker.position(0)
        decompressor.decompress(syncFlushMarker) { decodeAndFree(it) }
        decompressor.flush { decodeAndFree(it) }
        decoder.finish(sb)
        decoder.reset()

        return sb.toString()
    }

    private fun combineAll(chunks: List<ReadBuffer>): ReadBuffer {
        if (chunks.size == 1) {
            val c = chunks[0]
            c.position(0)
            return c
        }
        val total = chunks.sumOf { it.remaining() }
        val buf = factory.allocate(total)
        for (c in chunks) {
            c.position(0)
            buf.write(c)
        }
        buf.resetForRead()
        return buf
    }

    private fun stripSyncFlushMarker(buf: ReadBuffer): ReadBuffer {
        val rem = buf.remaining()
        if (rem < 4) return buf
        val pos = buf.position()
        buf.position(pos + rem - 4)
        val b0 = buf.readByte()
        val b1 = buf.readByte()
        val b2 = buf.readByte()
        val b3 = buf.readByte()
        buf.position(pos)
        if (b0 == 0x00.toByte() && b1 == 0x00.toByte() && b2 == 0xFF.toByte() && b3 == 0xFF.toByte()) {
            buf.setLimit(buf.limit() - 4)
        }
        return buf
    }
}
