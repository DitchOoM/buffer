package com.ditchoom.onebrc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkSplitterTest {
    // A small ASCII "file" probed byte-by-byte — no I/O, no ByteArray.
    private val content = "a;1.0\nbb;2.0\nccc;3.0\ndddd;4.0\n"
    private val byteAt: (Long) -> Byte = { content[it.toInt()].code.toByte() }
    private val size = content.length.toLong()

    @Test
    fun singleChunkCoversWholeFile() {
        val chunks = ChunkSplitter.split(size, 1, byteAt)
        assertEquals(1, chunks.size)
        assertEquals(Chunk(0, size), chunks[0])
    }

    @Test
    fun emptyFileYieldsNoChunks() {
        assertTrue(ChunkSplitter.split(0, 8) { 0 }.isEmpty())
    }

    @Test
    fun chunksPartitionFileWithoutGapsOrOverlap() {
        val chunks = ChunkSplitter.split(size, 4, byteAt)
        assertEquals(0L, chunks.first().start)
        assertEquals(size, chunks.last().endExclusive)
        for (i in 1 until chunks.size) {
            assertEquals(chunks[i - 1].endExclusive, chunks[i].start, "chunk $i must start where previous ends")
        }
    }

    @Test
    fun everyBoundaryLandsAfterANewline() {
        val chunks = ChunkSplitter.split(size, 4, byteAt)
        // Each chunk (except possibly via file end) ends immediately after a '\n'.
        for (chunk in chunks) {
            val lastByte = content[(chunk.endExclusive - 1).toInt()]
            assertEquals('\n', lastByte, "chunk ending at ${chunk.endExclusive} should end on a newline")
            // And it starts at the beginning of a line (offset 0 or right after a newline).
            if (chunk.start > 0) {
                assertEquals('\n', content[(chunk.start - 1).toInt()])
            }
        }
    }

    @Test
    fun moreChunksThanLinesStillPartitionsCleanly() {
        val chunks = ChunkSplitter.split(size, 100, byteAt)
        assertEquals(0L, chunks.first().start)
        assertEquals(size, chunks.last().endExclusive)
        var cursor = 0L
        for (chunk in chunks) {
            assertEquals(cursor, chunk.start)
            cursor = chunk.endExclusive
        }
    }
}
