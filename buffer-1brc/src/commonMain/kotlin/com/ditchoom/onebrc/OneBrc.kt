package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory

private const val SEMICOLON = ';'.code.toByte()
private const val NEWLINE = '\n'.code.toByte()

/**
 * Processes one chunk into its own [StationTable].
 *
 * The whole scan runs on the core buffer primitives: [com.ditchoom.buffer.ReadBuffer.indexOf] to
 * find the ';' and '\n' delimiters (SIMD/8-byte bulk), [com.ditchoom.buffer.ReadBuffer.readFixedDecimalTenths]
 * to parse the temperature, and hashRange/regionEquals inside [StationTable.merge] to key the map —
 * with zero per-row allocation.
 */
internal fun aggregateChunk(
    file: MappedFile,
    chunk: Chunk,
    factory: BufferFactory,
): StationTable {
    val length = chunk.length.toInt() // chunks are < 2GB by construction
    val buffer = file.region(chunk.start, length)
    val table = StationTable(factory)
    val limit = buffer.limit()
    var pos = 0
    while (pos < limit) {
        buffer.position(pos)
        val semiRel = buffer.indexOf(SEMICOLON)
        if (semiRel < 0) break
        val semi = pos + semiRel

        buffer.position(semi + 1)
        val newlineRel = buffer.indexOf(NEWLINE)
        val lineEnd = if (newlineRel < 0) limit else semi + 1 + newlineRel

        val tenths = buffer.readFixedDecimalTenths(semi + 1, lineEnd - (semi + 1))
        table.merge(buffer, pos, semi - pos, tenths)
        pos = lineEnd + 1
    }
    buffer.freeNativeMemory()
    return table
}

/**
 * The One Billion Row Challenge solver, built on the core :buffer primitives.
 *
 * Pipeline: mmap the file → split into newline-aligned chunks → aggregate each chunk in parallel
 * (one [StationTable] per worker) → merge → render sorted output. The expensive inner loop never
 * allocates per row.
 */
object OneBrc {
    fun solve(
        file: MappedFile,
        workers: Int = defaultParallelism(),
        factory: BufferFactory = onebrcDefaultFactory(),
    ): String {
        if (file.size == 0L) return "{}"
        val chunks = ChunkSplitter.split(file.size, workers) { file.byteAt(it) }
        val tables = runChunks(chunks) { chunk -> aggregateChunk(file, chunk, factory) }

        val merged = StationTable(factory)
        for (table in tables) {
            merged.mergeFrom(table)
            table.close()
        }
        val output = merged.formatOutput()
        merged.close()
        return output
    }

    fun solveFile(
        path: String,
        workers: Int = defaultParallelism(),
    ): String {
        val file = openMappedFile(path)
        return try {
            solve(file, workers)
        } finally {
            file.close()
        }
    }
}
