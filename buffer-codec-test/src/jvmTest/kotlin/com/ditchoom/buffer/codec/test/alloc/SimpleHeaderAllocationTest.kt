package com.ditchoom.buffer.codec.test.alloc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.simple.SimpleHeader
import com.ditchoom.buffer.codec.test.protocols.simple.SimpleHeaderCodec
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enforcement: zero per-call `[B` allocations
 * attributable to encode/decode of a codec on JVM. The build
 * runs `:jvmTest` with `-XX:-UseTLAB` so every allocation flows
 * through `jdk.ObjectAllocationOutsideTLAB` and the count is
 * deterministic per allocation rather than per-TLAB-rotation.
 *
 * What this catches: a future emitter regression that introduces
 * `text.encodeToByteArray()`, an intermediate `ByteArray` allocation
 * in the buffer runtime, or any `[B` `new` instruction inside
 * generated codec code paths. A regression shows ~50,000 events in a
 * single trigger group; the threshold leaves a 100x margin above
 * steady-state JFR noise.
 *
 * What this does *not* catch: JDK String internals (Compact Strings'
 * private `byte[]` allocated by `String.<init>`) — the doctrine only
 * claims attribution for our code, not the JDK's String contract.
 * Those events have `java.lang.String.<init>` as the trigger frame
 * and are filtered out by the trigger-frame scope.
 */
class SimpleHeaderAllocationTest {
    private val sample = SimpleHeader(id = 0x1234_5678, name = "héllo 🌍")

    @Test
    fun jfrPositiveControl_capturesDeliberateByteArray() {
        // Independent recording: just a deliberate byte[] allocation. JFR must
        // capture this and attribute it to this test method. If it doesn't,
        // the recording is broken (events not enabled, byte[] class name
        // mismatch, JFR module disabled) and any zero result from the workload
        // test below is meaningless.
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
        // Heavy warmup — JIT-compile every method on the hot path before
        // measurement starts. JIT compilation itself triggers byte[] allocations
        // (compiled-code buffers, OSR stubs) that get attributed to whichever
        // method triggered the compile; after stabilization those drop to zero.
        repeat(WARMUP_ITERATIONS) { roundTrip(sample) }

        val (_, report) =
            JfrAllocationTracker.recordByteArrayAllocations {
                repeat(MEASURED_ITERATIONS) { roundTrip(sample) }
            }

        // Regression detection — not "literally zero". Even at steady state JFR
        // attributes ~30 byte[] events per 50K iterations to our packages: JIT
        // recompilation, deoptimization, and async stack-walk imprecision all
        // produce events whose top frame is one of our methods even though no
        // user-code allocation actually happened. A real regression (e.g., the
        // emitter starts emitting `value.name.toByteArray()`) shows a single
        // method group with one event PER iteration — orders of magnitude above
        // the noise floor.
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
                "Stage C codec encode/decode shows a per-call byte[] allocation regression. " +
                    "Threshold: a single trigger method must not exceed $MAX_EVENTS_PER_GROUP " +
                    "events across $MEASURED_ITERATIONS iterations (largest=$largestGroupSize " +
                    "in ${largestGroup?.key}).\nFull breakdown:\n$breakdown",
            )
        }
    }

    private fun roundTrip(value: SimpleHeader) {
        val buf = BufferFactory.Default.allocate(64)
        SimpleHeaderCodec.encode(buf, value, EncodeContext.Empty)
        buf.resetForRead()
        SimpleHeaderCodec.decode(buf, DecodeContext.Empty)
    }

    private companion object {
        // Iteration counts can stay modest — the build's `-XX:-UseTLAB` jvmArg
        // makes every allocation flow through `jdk.ObjectAllocationOutsideTLAB`,
        // which is reported per-allocation rather than per-TLAB-rotation. The
        // warmup still runs to settle JIT compilation on the codec's hot path.
        private const val WARMUP_ITERATIONS = 100_000
        private const val MEASURED_ITERATIONS = 50_000

        // A real per-call byte[] regression would push a single trigger group
        // to >= MEASURED_ITERATIONS events. Steady-state JFR noise sits well
        // under 100 events per group across 50K iterations. 500 leaves a 100x
        // margin from observed noise to a real regression.
        private const val MAX_EVENTS_PER_GROUP = 500

        // Above 16 KB so the canary cannot land in any TLAB slack — guarantees
        // the event fires even if TLAB were somehow re-enabled.
        private const val SANITY_CANARY_BYTES = 32 * 1024
    }
}
