@file:Suppress("MagicNumber")

package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.LinearMemoryAllocator
import com.ditchoom.buffer.managed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compression must not stage payloads through the Kotlin/Wasm runtime's scoped allocator.
 *
 * That allocator opens every top-level scope at address 0 and bump-allocates upward, so staging a
 * payload through it walks across the buffer pool once the payload is larger than the gap the pool
 * keeps below itself. The gap cannot be sized around this: what gets staged is caller data, so the
 * scratch is as large as the payload and any fixed reserve is beaten by a large enough input.
 *
 * These tests pin the property rather than the mechanism — a live pool buffer's bytes must survive a
 * compression round-trip whose payload is several times the reserve. Both directions are covered
 * because the two conversions are separate code paths: `ByteArray -> JsByteArray` on the way in and
 * `JsByteArray -> PlatformBuffer` on the way back.
 */
class LargePayloadPoolIntegrityTest {
    /**
     * A byte the pattern check can be unambiguous about. The runtime's scratch carries compressible
     * caller data here, so a survivor check needs a value the payload never contains — see
     * [payloadByte], which is taken mod 251 and so never reaches 0xA5.
     */
    private val sentinel: Byte = 0xA5.toByte()

    private fun payloadByte(i: Int): Byte = (i % 251).toByte()

    @Test
    fun aPayloadSeveralTimesTheReserveLeavesLivePoolBuffersIntact() =
        runTest {
            val reserve = LinearMemoryAllocator.runtimeScratchReserveBytes
            val liveSize = 8192

            // Allocated from the pool *before* the round-trip, so it sits in the low pool offsets a
            // scratch bump from address 0 would reach first.
            val live = BufferFactory.Default.allocate(liveSize)
            try {
                repeat(liveSize) { live.writeByte(sentinel) }
                live.resetForRead()

                // Comfortably past the reserve, so the old staging path could not have stayed below
                // the pool no matter how the reserve were tuned.
                val payloadSize = reserve * 3
                val payload = ByteArray(payloadSize) { payloadByte(it) }

                val compressed = compressAsync(BufferFactory.Default.wrap(payload))
                val decompressed = decompressAsync(compressed)
                compressed.freeNativeMemory()

                // The payload itself must survive, which is what makes this a round-trip and not
                // just a memory assertion.
                assertEquals(
                    payloadSize,
                    decompressed.remaining(),
                    "the decompressed payload should be the same length as the input",
                )
                var mismatchAt = -1
                for (i in 0 until payloadSize) {
                    if (decompressed.readByte() != payloadByte(i)) {
                        mismatchAt = i
                        break
                    }
                }
                assertEquals(-1, mismatchAt, "decompressed payload differs from the input")

                // And the live buffer must be untouched. Under the old staging path the scratch bump
                // crossed the reserve and wrote through exactly this region.
                var clobberedAt = -1
                for (i in 0 until liveSize) {
                    if (live.readByte() != sentinel) {
                        clobberedAt = i
                        break
                    }
                }
                assertEquals(
                    -1,
                    clobberedAt,
                    "a live pool buffer was overwritten during a $payloadSize-byte round-trip, " +
                        "reserve=$reserve — payloads are reaching the pool again",
                )
                decompressed.freeNativeMemory()
            } finally {
                live.freeNativeMemory()
            }
        }

    /**
     * Every staging block the conversion takes has to go back to the pool.
     *
     * This drives the two conversions directly rather than a full round-trip, because
     * `compressAsync`/`decompressAsync` also allocate their own output from the factory and the
     * pipeline's internal chunk lifetime would swamp the measurement. What is measured here is only
     * the interop staging: linear memory is not garbage collected, so a `stageManaged` block that
     * missed its `finally` would step live bytes on every call while the integrity test above kept
     * passing.
     */
    @Test
    fun theStagingBlocksTheConversionsTakeAreAllReleased() {
        val reserve = LinearMemoryAllocator.runtimeScratchReserveBytes
        val payload = ByteArray(reserve * 2) { payloadByte(it) }

        // Warm the path once so one-time pool growth is not counted against the measured calls.
        stageBothDirections(payload)

        val before = LinearMemoryAllocator.getAllocationStats()
        repeat(3) { stageBothDirections(payload) }
        val after = LinearMemoryAllocator.getAllocationStats()

        val liveBefore = before.totalAllocated - before.freeListBytes
        val liveAfter = after.totalAllocated - after.freeListBytes
        assertTrue(
            liveAfter <= liveBefore,
            "the interop staging leaked ${liveAfter - liveBefore} bytes of linear memory across " +
                "3 conversions (live before=$liveBefore, after=$liveAfter)",
        )
    }

    /**
     * Both staging directions over a managed buffer: out through `ByteArray -> JsByteArray`, and
     * back through `JsByteArray -> PlatformBuffer` with a factory that yields no native memory, so
     * the copy-back fallback is the path taken. Neither end may retain a pool block.
     */
    private fun stageBothDirections(payload: ByteArray) {
        val js = BufferFactory.Default.wrap(payload).toJsByteArray()
        js.toPlatformBuffer(BufferFactory.managed())
    }
}
