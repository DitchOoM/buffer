package com.ditchoom.buffer

import com.ditchoom.buffer.pool.withBuffer
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Phase 0.5 — load-bearing pool-safety proof for `copyToByteArray`.
 *
 * This is the actual bug class the lockdown closes: a consumer holds a
 * `ByteArray` past the pool's `withBuffer` block; the same pool slot then
 * re-rents that buffer to a different request which overwrites its bytes.
 * If `copyToByteArray` aliased the pool buffer's storage (instead of
 * allocating a fresh ByteArray), the consumer would now read the second
 * request's bytes — a silent cross-tenant leak.
 *
 * Method:
 *   1. Acquire a buffer from a small pool (maxPoolSize = 2) and fill it
 *      with `originalBytes`.
 *   2. Inside the `withBuffer` block, capture
 *      `held = buf.copyToByteArray(remaining)`.
 *   3. After the block returns (buffer back in pool), acquire again from
 *      the same pool and overwrite with `differentBytes`. With a small
 *      pool the same underlying storage is highly likely to be reused.
 *   4. Assert `held` still equals `originalBytes`.
 *
 * Runs across both factories with one mid-sized payload — exhaustively
 * exercising the JVM/Apple/Native/JS branches without bloating runtime.
 */
class PooledBufferConsumerEscapeTests {
    private val size = 16 * 1024

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

    private fun originalBytes(): ByteArray = ByteArray(size) { (it and 0x7F).toByte() }

    private fun differentBytes(): ByteArray = ByteArray(size) { ((it and 0x7F) xor 0x55).toByte() }

    @Test
    fun pooledBufferAfterReleaseDoesNotLeakToConsumer() {
        for ((name, factory) in factories()) {
            val original = originalBytes()
            val different = differentBytes()

            withPool(maxPoolSize = 2, defaultBufferSize = size, factory = factory) { pool ->
                var held: ByteArray? = null
                pool.withBuffer(size) { buf ->
                    buf.writeBytes(original)
                    buf.resetForRead()
                    held = buf.copyToByteArray(buf.remaining())
                }
                // Buffer is back in pool. Re-acquire and overwrite — same
                // underlying storage is very likely to be reused.
                pool.withBuffer(size) { buf2 ->
                    buf2.writeBytes(different)
                }
                assertContentEquals(
                    original,
                    held,
                    "factory=$name: held bytes from copyToByteArray must survive pool churn",
                )
            }
        }
    }
}
