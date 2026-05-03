package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.simple.Command
import com.ditchoom.buffer.codec.test.protocols.simple.CommandCodec
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Locked Decision row 16 enforcement extended to Stage D's sealed
 * dispatcher. The fixed-size `Ping` variant exercises the
 * all-scalar dispatch path; the `Echo` variant exercises the
 * BackPatch path through the dispatcher into the `@LengthPrefixed
 * val: String` body. A regression here would surface as a per-call
 * `[B` allocation attributable to the dispatcher or one of its
 * delegated variant codecs — e.g., a future emitter mistake that
 * stages variants through `text.encodeToByteArray()` before
 * writing, or any new intermediate `ByteArray` shows up as a
 * per-iteration spike in the trigger group breakdown.
 *
 * Mirrors `SimpleHeaderAllocationTest`'s setup: same warmup count,
 * same JFR positive control (separate test method), same
 * regression threshold. The build's `-XX:-UseTLAB` jvmArg keeps
 * each allocation reported individually rather than per-TLAB
 * rotation, which is what makes the threshold meaningful.
 */
class CommandAllocationTest {
    private val ping = Command.Ping(ts = 0x1122_3344_5566_7788L)
    private val echo = Command.Echo(msg = "héllo 🌍")

    @Test
    fun jfrPositiveControl_capturesDeliberateByteArray() {
        val (_, report) =
            JfrAllocationTracker.recordByteArrayAllocations {
                @Suppress("UNUSED_VARIABLE")
                val canary = ByteArray(SANITY_CANARY_BYTES)
            }
        assertTrue(
            report.attributedToUs.isNotEmpty(),
            "JFR positive control failed — ByteArray($SANITY_CANARY_BYTES) was not captured " +
                "(totalByteArrayEvents=${report.totalByteArrayEvents}). Recording is misconfigured.",
        )
    }

    @Test
    fun encodeDecode_noByteArrayAllocationsAttributableToOurCode() {
        repeat(WARMUP_ITERATIONS) {
            roundTrip(ping)
            roundTrip(echo)
        }

        val (_, report) =
            JfrAllocationTracker.recordByteArrayAllocations {
                repeat(MEASURED_ITERATIONS) {
                    roundTrip(ping)
                    roundTrip(echo)
                }
            }

        val groups =
            report.attributedToUs.groupBy { "${it.triggerClass}.${it.triggerMethod}" }
        val largestGroup = groups.maxByOrNull { it.value.size }
        val largestGroupSize = largestGroup?.value?.size ?: 0
        if (largestGroupSize > MAX_EVENTS_PER_GROUP) {
            val breakdown =
                groups.entries
                    .sortedByDescending { it.value.size }
                    .joinToString("\n") { (key, group) ->
                        val totalBytes = group.sumOf { it.size }
                        "  $key — ${group.size} events, $totalBytes bytes total"
                    }
            assertTrue(
                false,
                "Stage D dispatcher encode/decode shows a per-call byte[] allocation regression. " +
                    "Threshold: a single trigger method must not exceed $MAX_EVENTS_PER_GROUP " +
                    "events across ${MEASURED_ITERATIONS * 2} iterations (largest=$largestGroupSize " +
                    "in ${largestGroup?.key}).\nFull breakdown:\n$breakdown",
            )
        }
    }

    private fun roundTrip(value: Command) {
        val buf = BufferFactory.Default.allocate(64)
        CommandCodec.encode(buf, value, EncodeContext.Empty)
        buf.resetForRead()
        CommandCodec.decode(buf, DecodeContext.Empty)
    }

    private companion object {
        private const val WARMUP_ITERATIONS = 100_000
        private const val MEASURED_ITERATIONS = 50_000
        private const val MAX_EVENTS_PER_GROUP = 500
        private const val SANITY_CANARY_BYTES = 32 * 1024
    }
}
