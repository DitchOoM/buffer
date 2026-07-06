package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for a per-frame pooled-buffer leak in [StreamProcessor.readBuffer].
 *
 * When a chunk is consumed EXACTLY (`chunk.remaining() == size`) and its position is
 * already `> 0` (framing bytes — e.g. a WebSocket frame header — were read first),
 * `readBuffer` removed the chunk from its deque and returned `chunk.slice()`. `slice()`
 * bumps the [com.ditchoom.buffer.pool.PooledBuffer] refcount, but the deque's original
 * chunk reference was dropped without being released — so after the caller frees the
 * returned slice the refcount is stuck at 1 and the underlying buffer never returns to the
 * pool. Each such read leaks one full pool buffer.
 *
 * This is the real-socket path behind the Autobahn cat-12 ~1 GiB direct-memory OOM: the
 * socket read pool hands the codec a full-size (hundreds of KB) buffer per frame; the
 * WebSocket frame reader consumes the 2–14 byte header (position > 0) then reads the payload
 * with an exact `readBuffer(payloadSize)`, hitting this branch once per message. Under
 * `deterministic()` (shared-arena FFM, no GC reclamation) the stranded buffers accumulate to
 * the direct-memory cap and OOM.
 */
class StreamProcessorReadBufferPoolLeakTest {
    /** One frame: header bytes consumed, then an exact readBuffer of the payload. */
    @Test
    fun readBufferExactWithConsumedHeaderReturnsChunkToPool() {
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val processor = StreamProcessor.create(pool)

        val headerSize = 2
        val payloadSize = 10

        // A pooled chunk carrying [header || payload], as a socket read would deliver.
        val chunk = pool.acquire(headerSize + payloadSize) as PlatformBuffer
        repeat(headerSize + payloadSize) { chunk.writeByte(it.toByte()) }
        chunk.resetForRead()
        processor.append(chunk)

        // Consume the header → chunk.position() advances to headerSize (> 0).
        repeat(headerSize) { processor.readByte() }

        // Read the payload exactly. Buggy path: removeFirst + return slice, original
        // chunk reference orphaned.
        val payload = processor.readBuffer(payloadSize)
        assertEquals(payloadSize, payload.remaining(), "payload view must expose exactly the payload")

        // Consumer frees the returned buffer (the codec does this after copying the payload).
        (payload as PlatformBuffer).freeNativeMemory()

        assertEquals(
            1,
            pool.stats().currentPoolSize,
            "After the consumer frees the payload, the pooled read buffer must be back in the pool " +
                "(refcount reached 0) — not stranded.",
        )
        pool.clear()
    }

    /** The hot path: one frame per pool buffer, repeated. Every acquire after the first must reuse. */
    @Test
    fun repeatedFrameReadsConvergeToFullPoolReuse() {
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val processor = StreamProcessor.create(pool)
        val headerSize = 2
        val payloadSize = 64
        val iterations = 200

        repeat(iterations) {
            val chunk = pool.acquire(headerSize + payloadSize) as PlatformBuffer
            repeat(headerSize + payloadSize) { i -> chunk.writeByte(i.toByte()) }
            chunk.resetForRead()
            processor.append(chunk)
            repeat(headerSize) { processor.readByte() }
            val payload = processor.readBuffer(payloadSize)
            (payload as PlatformBuffer).freeNativeMemory()
        }

        val stats = pool.stats()
        assertEquals(iterations.toLong(), stats.totalAllocations)
        assertEquals(1L, stats.poolMisses, "Only the very first frame should miss the pool; the rest reuse.")
        assertEquals((iterations - 1).toLong(), stats.poolHits)
        pool.clear()
    }
}
