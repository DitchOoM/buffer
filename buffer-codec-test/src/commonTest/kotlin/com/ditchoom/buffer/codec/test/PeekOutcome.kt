package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Test-side reading of a [FrameDetector.frameSize] answer against what has arrived:
 * the frame is [Complete] once its declared total is buffered. Keeps assertions about
 * complete versus still-arriving frames short.
 */
sealed interface PeekOutcome {
    data class Complete(
        val bytes: Int,
    ) : PeekOutcome

    data object NeedsMoreData : PeekOutcome

    data object NoFraming : PeekOutcome
}

fun FrameDetector.peekOutcome(
    stream: StreamProcessor,
    baseOffset: Int = 0,
): PeekOutcome =
    frameSize(stream, baseOffset).foldTotal(
        declared = { total ->
            if (total <= stream.available() - baseOffset) PeekOutcome.Complete(total.toInt()) else PeekOutcome.NeedsMoreData
        },
        needsMoreData = { PeekOutcome.NeedsMoreData },
        unframed = { PeekOutcome.NoFraming },
    )
