package com.ditchoom.onebrc

/**
 * A half-open byte range [start, endExclusive) of the input file assigned to one worker.
 * Boundaries are always snapped to just after a '\n', so every chunk contains whole lines only.
 */
data class Chunk(
    val start: Long,
    val endExclusive: Long,
) {
    val length: Long get() = endExclusive - start
}

/**
 * Splits a file into newline-aligned chunks for parallel processing.
 *
 * Pure and platform-agnostic: takes the file size and a single-byte probe so it can be unit-tested
 * without any I/O. Each cut point is computed proportionally (`size * k / n`) and then advanced
 * forward to the byte after the next '\n', guaranteeing every line belongs to exactly one chunk.
 */
object ChunkSplitter {
    private const val NEWLINE = '\n'.code.toByte()

    fun split(
        fileSize: Long,
        desiredChunks: Int,
        byteAt: (Long) -> Byte,
    ): List<Chunk> {
        val n = if (desiredChunks < 1) 1 else desiredChunks
        return when {
            fileSize <= 0L -> emptyList()
            n == 1 -> listOf(Chunk(0L, fileSize))
            else -> splitMultiple(fileSize, n, byteAt)
        }
    }

    private fun splitMultiple(
        fileSize: Long,
        n: Int,
        byteAt: (Long) -> Byte,
    ): List<Chunk> {
        val chunks = ArrayList<Chunk>(n)
        var start = 0L
        for (k in 1 until n) {
            var pos = fileSize * k / n
            if (pos < start) pos = start
            while (pos < fileSize && byteAt(pos) != NEWLINE) pos++
            if (pos < fileSize) pos++ // include the newline; the next chunk starts after it
            if (pos > start) {
                chunks.add(Chunk(start, pos))
                start = pos
            }
            if (start >= fileSize) break
        }
        if (start < fileSize) chunks.add(Chunk(start, fileSize))
        return chunks
    }
}
