package com.ditchoom.buffer.codec.test.alloc

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import java.time.Duration

/**
 * JVM-only helper that records `[B` (byte array) allocations via JFR
 * during a workload and returns the events whose immediate trigger
 * frame is in `com.ditchoom.buffer` or `com.ditchoom.buffer.codec`
 * (the "attributable" set per ).
 *
 * Both `jdk.ObjectAllocationInNewTLAB` and
 * `jdk.ObjectAllocationOutsideTLAB` are enabled with zero threshold so
 * outside-TLAB allocations are reported in full and TLAB-rotation
 * allocations are reported as TLAB pages exhaust. The test is expected
 * to drive enough iterations that the TLAB rotates several times — a
 * single round-trip may slip through because the existing TLAB still
 * has slack.
 *
 * Filtering happens on the stack trace's *immediate trigger frame*
 * (frame 0) rather than any frame in the chain. The JDK's
 * `String.<init>(byte[], byte)` allocates a Compact-Strings byte[] —
 * those events have `java.lang.String.<init>` as the trigger and
 * `com.ditchoom.buffer.BaseJvmBuffer.readString` further down the
 * chain. Counting "any frame matches" would mis-attribute String
 * internals to the buffer; the trigger-frame filter scopes the test
 * to allocations that originate in our code.
 */
internal data class AttributedAllocation(
    val objectClass: String,
    val size: Long,
    val triggerClass: String,
    val triggerMethod: String,
)

internal data class AllocationReport(
    val attributedToUs: List<AttributedAllocation>,
    val totalByteArrayEvents: Int,
)

internal object JfrAllocationTracker {
    private val OWNED_PACKAGES =
        listOf(
            "com.ditchoom.buffer.codec.test.protocols",
            "com.ditchoom.buffer.codec",
            "com.ditchoom.buffer",
        )

    fun <T> recordByteArrayAllocations(block: () -> T): Pair<T, AllocationReport> {
        val recording = Recording()
        recording.enable("jdk.ObjectAllocationInNewTLAB").withThreshold(Duration.ZERO).withStackTrace()
        recording.enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(Duration.ZERO).withStackTrace()
        recording.start()
        val result: T
        try {
            result = block()
        } finally {
            recording.stop()
        }
        val tmp = Files.createTempFile("ditchoom-codec-alloc-", ".jfr")
        try {
            recording.dump(tmp)
            val attributed = mutableListOf<AttributedAllocation>()
            var totalByteArrayEvents = 0
            RecordingFile(tmp).use { rf ->
                while (rf.hasMoreEvents()) {
                    val event = rf.readEvent()
                    // JFR uses the JVM internal descriptor — `[B` for byte[].
                    val byteArrayClassName = byteArrayClassNameOf(event) ?: continue
                    totalByteArrayEvents++
                    attributeOwnedAllocation(event, byteArrayClassName)?.let { attributed += it }
                }
            }
            return result to AllocationReport(attributed, totalByteArrayEvents)
        } finally {
            recording.close()
            Files.deleteIfExists(tmp)
        }
    }

    /** Returns the object class name when [event] is a `[B` (byte[]) allocation, else null. */
    private fun byteArrayClassNameOf(event: RecordedEvent): String? {
        val cls = event.getValue<RecordedClass?>("objectClass") ?: return null
        return cls.name.takeIf { it == "[B" }
    }

    /** True when [frame]'s declaring class is in one of our owned packages. */
    private fun isOwnedTrigger(frame: RecordedFrame): Boolean {
        val triggerClass = frame.method.type.name
        return OWNED_PACKAGES.any { triggerClass.startsWith(it) }
    }

    /**
     * Attributes a byte-array allocation to our code when its immediate trigger frame is in an
     * owned package, else returns null (so the caller skips it without a loop jump).
     */
    private fun attributeOwnedAllocation(
        event: RecordedEvent,
        objectClass: String,
    ): AttributedAllocation? {
        val trigger =
            event.stackTrace
                ?.frames
                ?.firstOrNull()
                ?.takeIf(::isOwnedTrigger) ?: return null
        val triggerClass = trigger.method.type.name
        val size =
            runCatching { event.getLong("allocationSize") }
                .recoverCatching { event.getLong("tlabSize") }
                .getOrDefault(0L)
        return AttributedAllocation(
            objectClass = objectClass,
            size = size,
            triggerClass = triggerClass,
            triggerMethod = trigger.method.name,
        )
    }
}
