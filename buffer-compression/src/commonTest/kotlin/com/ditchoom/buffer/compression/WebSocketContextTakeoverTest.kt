package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
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
    private val syncFlushMarker = factory.wrap(byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))

    private fun stringToBuffer(s: String): ReadBuffer {
        val buf = factory.allocate(s.length * 3)
        buf.writeString(s, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    @Test
    fun twoMessagesWithContextTakeover() {
        if (!supportsSyncCompression) return
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                bufferFactory = BufferFactory.Default,
            )
        val decompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                bufferFactory = BufferFactory.Default,
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
        if (!supportsSyncCompression) return
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                bufferFactory = BufferFactory.Default,
                windowBits = -9,
            )
        val decompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                bufferFactory = BufferFactory.Default,
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
        if (!supportsSyncCompression) return
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                bufferFactory = BufferFactory.Default,
            )
        val decompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                bufferFactory = BufferFactory.Default,
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
        val compressedOutput = factory.allocate(input.length * 3 + 1024)
        compressor.compressScoped(stringToBuffer(input)) { compressedOutput.write(this) }
        compressor.flushScoped { compressedOutput.write(this) }
        compressedOutput.resetForRead()

        // Strip sync flush marker from end (permessage-deflate requirement)
        val stripped = stripSyncFlushMarker(compressedOutput)

        // Step 2: Decompress (same as websocket's decompressToStringSync)
        val sb = StringBuilder()
        val decoder = reusedDecoder ?: StreamingStringDecoder()

        decompressor.decompressScoped(stripped) {
            if (position() != 0) position(0)
            if (remaining() > 0) decoder.decode(this, sb)
        }
        syncFlushMarker.position(0)
        decompressor.decompressScoped(syncFlushMarker) {
            if (position() != 0) position(0)
            if (remaining() > 0) decoder.decode(this, sb)
        }
        decompressor.flushScoped {
            if (position() != 0) position(0)
            if (remaining() > 0) decoder.decode(this, sb)
        }
        decoder.finish(sb)
        decoder.reset()

        return sb.toString()
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
