package com.ditchoom.buffer.stream

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FrameDetectorTest {
    @Test
    fun peekResultSizeHoldsBytes() {
        val result = PeekResult.Size(42)
        assertEquals(42, result.bytes)
    }

    @Test
    fun peekResultExhaustiveWhen() {
        val results = listOf(PeekResult.Size(10), PeekResult.NeedsMoreData)
        val descriptions =
            results.map { result ->
                when (result) {
                    is PeekResult.Size -> "size:${result.bytes}"
                    PeekResult.NeedsMoreData -> "needsMore"
                }
            }
        assertEquals(listOf("size:10", "needsMore"), descriptions)
    }

    @Test
    fun frameDetectorSamConversion() {
        // fun interface enables SAM lambda
        val detector = FrameDetector { _, _ -> PeekResult.Size(4) }
        val result = detector.peekFrameSize(fakeStreamProcessor(), 0)
        assertIs<PeekResult.Size>(result)
        assertEquals(4, result.bytes)
    }

    @Test
    fun frameDetectorReturnsNeedsMoreDataWhenInsufficient() =
        withPool(defaultBufferSize = 64) { pool ->
            val processor = StreamProcessor.create(pool)
            // Empty stream — not enough data
            val detector =
                FrameDetector { stream, baseOffset ->
                    if (stream.available() < 4) {
                        PeekResult.NeedsMoreData
                    } else {
                        PeekResult.Size(stream.peekInt(baseOffset))
                    }
                }
            assertEquals(PeekResult.NeedsMoreData, detector.peekFrameSize(processor, 0))
        }

    @Test
    fun frameDetectorReturnsSizeWithSufficientData() =
        withPool(defaultBufferSize = 64) { pool ->
            val processor = StreamProcessor.create(pool)
            val buf = BufferFactory.managed().allocate(8)
            buf.writeInt(20) // frame size = 20
            buf.writeInt(0) // padding
            buf.resetForRead()
            processor.append(buf)

            val detector =
                FrameDetector { stream, baseOffset ->
                    if (stream.available() < 4) {
                        PeekResult.NeedsMoreData
                    } else {
                        PeekResult.Size(stream.peekInt(baseOffset))
                    }
                }
            val result = detector.peekFrameSize(processor, 0)
            assertIs<PeekResult.Size>(result)
            assertEquals(20, result.bytes)
        }

    /** Minimal StreamProcessor for tests that don't need a real one. */
    private fun fakeStreamProcessor(): StreamProcessor =
        withPool(defaultBufferSize = 16) { pool ->
            StreamProcessor.create(pool)
        }
}
