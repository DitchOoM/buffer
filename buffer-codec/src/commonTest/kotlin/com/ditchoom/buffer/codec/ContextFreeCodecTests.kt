package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.Utf8
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.unwrapFully
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A value with one fixed-width and one variable-width field, so both encode paths are exercised. */
private data class Greeting(
    val id: Int,
    val text: String,
)

/**
 * Context-free by construction: neither [encode] nor [wireSize] reads a single context key, so the
 * same [Greeting] produces the same bytes on every connection — the precondition [ContextFreeCodec]
 * declares and [encodeShared] relies on.
 */
private object GreetingCodec : ContextFreeCodec<Greeting> {
    override fun encode(
        buffer: WriteBuffer,
        value: Greeting,
        context: EncodeContext,
    ) {
        buffer.writeInt(value.id)
        buffer.writeInt(Utf8.Strict.size(value.text))
        buffer.writeText(value.text, Utf8.Strict)
    }

    override fun wireSize(
        value: Greeting,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(Int.SIZE_BYTES * 2 + Utf8.Strict.size(value.text))

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Greeting {
        val id = buffer.readInt()
        val length = buffer.readInt()
        return Greeting(id, buffer.readText(length, Utf8.Strict))
    }
}

/**
 * Same wire format as [GreetingCodec] but reports [WireSize.BackPatch] with a deliberately tiny
 * [sizeHint], so `encodeToPlatformBuffer`'s grow-and-retry loop runs (and frees its discarded
 * attempts) underneath [encodeShared].
 */
private object GrowingGreetingCodec : ContextFreeCodec<Greeting> {
    override fun encode(
        buffer: WriteBuffer,
        value: Greeting,
        context: EncodeContext,
    ) = GreetingCodec.encode(buffer, value, context)

    override fun sizeHint(
        value: Greeting,
        context: EncodeContext,
    ): Int = 1

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Greeting = GreetingCodec.decode(buffer, context)
}

/**
 * [encodeShared] — encode once, fan out to many consumers.
 *
 * What must hold: the shared bytes are indistinguishable from a plain encode (otherwise sharing
 * silently corrupts one of the connections), every consumer reads the full frame through its own
 * cursor, and the storage is released exactly once when the creator's [SharedFrame.close] follows
 * the last consumer's release — including when the bytes came from a pool.
 */
@OptIn(ExperimentalFanoutApi::class)
class ContextFreeCodecTests {
    private val message = Greeting(0x0BADCAFE, "fan-out consumers")

    // ========================================================================
    // 6. Byte-identical to a plain encode
    // ========================================================================

    @Test
    fun sharedBytesAreIdenticalToAPlainEncodeOfTheSameMessage() {
        val plain = GreetingCodec.encodeToPlatformBuffer(message)
        val frame = GreetingCodec.encodeShared(message)
        try {
            assertEquals(plain.remaining(), frame.bytes.size, "shared frame length must match a plain encode")
            assertTrue(frame.bytes.withView { plain.contentEquals(it) }, "shared frame bytes must match a plain encode")

            // contentEquals is non-consuming, so compare the raw bytes independently too.
            val expected = plain.copyToByteArray(plain.remaining())
            val actual = frame.bytes.withView { it.copyToByteArray(frame.bytes.size) }
            assertContentEquals(expected, actual)

            assertEquals(message, frame.bytes.withView { GreetingCodec.decode(it, DecodeContext.Empty) })
        } finally {
            plain.freeNativeMemory()
            frame.close()
        }
    }

    @Test
    fun backPatchSizedEncodeSharesTheSameBytesAfterGrowAndRetry() {
        val plain = GrowingGreetingCodec.encodeToPlatformBuffer(message)
        val frame = GrowingGreetingCodec.encodeShared(message)
        try {
            assertEquals(plain.remaining(), frame.bytes.size)
            assertTrue(frame.bytes.withView { plain.contentEquals(it) })
            assertEquals(message, frame.bytes.withView { GrowingGreetingCodec.decode(it, DecodeContext.Empty) })
        } finally {
            plain.freeNativeMemory()
            frame.close()
        }
    }

    // ========================================================================
    // 7. Full frame lifecycle: 3 fan-out consumers
    // ========================================================================

    @Test
    fun threeConsumersEachReadTheWholeFrameAndTheStorageIsFreedOnce() {
        val pool = newPool()
        val frame = GreetingCodec.encodeShared(message, pool)
        assertSame(message, frame.origin, "loss reporting hands back the message, never a buffer")

        val perConsumerBytes = mutableListOf<ByteArray>()
        repeat(CONSUMERS) { consumer ->
            // Fan-out reference protocol: retain on transfer, read through an own cursor, release
            // exactly once when that connection is done with the bytes.
            val shared = frame.bytes.retain()
            perConsumerBytes += shared.withView { it.copyToByteArray(shared.size) }
            assertEquals(
                message,
                shared.withView { GreetingCodec.decode(it, DecodeContext.Empty) },
                "consumer $consumer decoded a different value",
            )
            shared.release()
            assertEquals(
                0,
                pool.stats().currentPoolSize,
                "the creator still holds a reference — consumer $consumer's release must not free",
            )
        }

        for (consumer in 1 until CONSUMERS) {
            assertContentEquals(
                perConsumerBytes[0],
                perConsumerBytes[consumer],
                "consumer $consumer saw different bytes than consumer 0",
            )
        }

        frame.close()
        assertEquals(1, pool.stats().currentPoolSize, "close() drops the creator's reference and frees the storage")

        assertFailsWith<IllegalStateException>("a second close is an over-release") { frame.close() }
        assertEquals(1, pool.stats().currentPoolSize, "over-release must not re-pool the storage")
        pool.clear()
    }

    // ========================================================================
    // 8. Pooled factory
    // ========================================================================

    @Test
    fun encodeSharedWithAPooledFactoryRoundTripsAndReturnsTheBufferToThePool() {
        val pool = newPool()
        val frame = GreetingCodec.encodeShared(message, pool)

        assertEquals(0, pool.stats().currentPoolSize, "the encoded chunk is checked out of the pool")
        assertEquals(
            WireSize.Exact(frame.bytes.size),
            GreetingCodec.wireSize(message, EncodeContext.Empty),
            "the frame holds exactly the encoded bytes, not the pooled chunk's capacity",
        )
        assertEquals(message, frame.bytes.withView { GreetingCodec.decode(it, DecodeContext.Empty) }, "round-trip")

        frame.close()
        assertEquals(1, pool.stats().currentPoolSize, "close() must return the pooled chunk to its pool")

        val reacquired = pool.acquire(frame.bytes.size) as PlatformBuffer
        assertTrue(pool.stats().poolHits >= 1, "the returned chunk must be genuinely reusable")
        reacquired.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun aPooledFrameSurvivesRepeatedFanOutCyclesWithoutLeakingChunks() {
        val pool = newPool()
        var storage: ReadBuffer? = null

        repeat(CYCLES) {
            val frame = GreetingCodec.encodeShared(message, pool)
            val shared = frame.bytes.retain()
            assertEquals(message, shared.withView { GreetingCodec.decode(it, DecodeContext.Empty) })
            shared.release()
            frame.close()

            // Every cycle must recycle the very same chunk — otherwise a reference is being leaked.
            val chunk = pool.acquire(POOL_BUFFER_SIZE) as PlatformBuffer
            val raw = chunk.unwrapFully()
            if (storage == null) storage = raw else assertSame(storage, raw, "a chunk leaked out of the pool")
            chunk.freeNativeMemory()
        }
        pool.clear()
    }

    private fun newPool(): BufferPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 4,
            defaultBufferSize = POOL_BUFFER_SIZE,
            factory = BufferFactory.Default,
        )

    private companion object {
        const val CONSUMERS = 3
        const val CYCLES = 8
        const val POOL_BUFFER_SIZE = 256
    }
}
