package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ByteSink.write] may accept a buffer only in PART — its contract calls the post-write position "the
 * resume point for a partial write's residue". A caller that issues one `write` and discards the count
 * silently drops the tail, and for a length-prefixed protocol that is corruption rather than loss: the
 * peer reads on to the already-declared length and swallows whatever follows.
 *
 * [writeFully] is the resumption primitive; these tests pin it, and pin the two defaults that were
 * getting it wrong ([ByteSink.writeGathered] and the typed [CodecSink]).
 */
class WriteFullyTests {
    /**
     * Accepts at most [acceptPerWrite] bytes per call and reports the count. [advanceCursor] selects
     * which real sink shape to imitate: contract-compliant (cursor consumed by what was taken) or the
     * zero-copy kind that hands a native address to the platform and leaves the cursor alone.
     */
    private class PartialAcceptSink(
        private val acceptPerWrite: Int,
        private val advanceCursor: Boolean = true,
    ) : ByteSink {
        val wire = ArrayList<Byte>()
        var writeCalls = 0
            private set

        override val isOpen: Boolean get() = true
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            writeCalls++
            val start = buffer.position()
            val take = minOf(acceptPerWrite, buffer.remaining())
            for (i in 0 until take) wire += buffer.readByte()
            if (!advanceCursor) buffer.position(start)
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    /** Reports zero progress forever — a broken sink, not back-pressure. */
    private class StalledSink : ByteSink {
        override val isOpen: Boolean get() = true
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = BytesWritten(0)

        override suspend fun close() = Unit
    }

    private fun bufferOf(bytes: ByteArray) =
        BufferFactory.Default.allocate(bytes.size).apply {
            writeBytes(bytes)
            resetForRead()
        }

    private fun pattern(n: Int) = ByteArray(n) { ((it * 31 + it / 251) % 256).toByte() }

    @Test
    fun writeFullyResumesUntilTheBufferIsDrained() =
        runTest {
            val payload = pattern(10_000)
            val sink = PartialAcceptSink(acceptPerWrite = 1_400)

            sink.writeFully(bufferOf(payload), 1.seconds)

            assertEquals(payload.toList(), sink.wire.toList())
            assertTrue(sink.writeCalls > 1, "the sink was never asked to resume — the test proves nothing")
        }

    /** A zero-copy sink that never moves the cursor must still be resumed byte-exactly. */
    @Test
    fun writeFullyResumesASinkThatLeavesTheCursorAlone() =
        runTest {
            val payload = pattern(8_000)
            val sink = PartialAcceptSink(acceptPerWrite = 900, advanceCursor = false)

            sink.writeFully(bufferOf(payload), 1.seconds)

            assertEquals(payload.toList(), sink.wire.toList())
        }

    @Test
    fun writeFullyFailsLoudlyWhenTheSinkNeverMakesProgress() =
        runTest {
            val failure =
                assertFailsWith<ByteSinkStalledException> {
                    StalledSink().writeFully(bufferOf(pattern(64)), 1.seconds)
                }
            assertEquals(0, failure.accepted)
            assertEquals(64, failure.pending)
        }

    /**
     * The gather-write defect: summing partial counts and advancing to the next buffer emits the first
     * buffer's prefix immediately followed by the SECOND buffer's bytes, dropping the remainder from
     * the middle of the stream. Callers gather because the pieces are adjacent (a header and its
     * payload), so that hole splices payload bytes into the header's declared region.
     */
    @Test
    fun writeGatheredCompletesEachBufferBeforeStartingTheNext() =
        runTest {
            val header = pattern(300)
            val payload = pattern(9_000)
            val sink = PartialAcceptSink(acceptPerWrite = 128)

            val written = sink.writeGathered(listOf(bufferOf(header), bufferOf(payload)), 1.seconds)

            assertEquals(header.size + payload.size, written.count, "the reported total must cover every byte")
            assertEquals(
                (header + payload).toList(),
                sink.wire.toList(),
                "gathered buffers must arrive contiguously — a partial write must be resumed, not skipped",
            )
        }

    @Test
    fun writeGatheredReportsEveryByteEvenWhenAcceptedOneAtATime() =
        runTest {
            val a = pattern(50)
            val b = pattern(70)
            val sink = PartialAcceptSink(acceptPerWrite = 1)

            val written = sink.writeGathered(listOf(bufferOf(a), bufferOf(b)), 1.seconds)

            assertEquals(a.size + b.size, written.count)
            assertEquals((a + b).toList(), sink.wire.toList())
        }
}
