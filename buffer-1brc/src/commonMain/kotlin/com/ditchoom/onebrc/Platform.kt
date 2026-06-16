package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer

/**
 * A memory-mapped (or, where mmap is unavailable, read-into-memory) view of the input file.
 *
 * The library deliberately ships no file I/O — this is the app-level seam that bridges a file into
 * the core buffer primitives. On JVM/Native the regions are zero-copy mmap views wrapped as
 * [PlatformBuffer] (via the existing DirectJvmBuffer / wrapNativeAddress paths).
 */
interface MappedFile {
    /** Total file size in bytes. */
    val size: Long

    /** A [PlatformBuffer] view of [length] bytes starting at file [offset]. length must be <= 2GB. */
    fun region(
        offset: Long,
        length: Int,
    ): PlatformBuffer

    /** Reads a single byte at file [offset] — used only by the chunk splitter. */
    fun byteAt(offset: Long): Byte

    fun close()
}

/** Opens [path] as a [MappedFile]. Platform-specific (FileChannel.map / posix mmap / node fs). */
expect fun openMappedFile(path: String): MappedFile

/** Number of parallel workers to use by default (typically the CPU core count). */
expect fun defaultParallelism(): Int

/**
 * The buffer factory the solver allocates its key arena from. Native memory with explicit cleanup
 * (`deterministic`) on JVM/Native for zero GC pressure; GC-managed heap on JS/WASM, where the
 * `Default` direct buffer is a bounded bump allocator unsuitable for repeated allocation.
 */
expect fun onebrcDefaultFactory(): BufferFactory

/**
 * Runs [task] over each chunk, returning one result per chunk. Platform-specific concurrency
 * (thread pool on JVM, Worker on Native, single-threaded loop on JS/WASM). Generic so it does not
 * expose the internal aggregation type.
 */
expect fun <T> runChunks(
    chunks: List<Chunk>,
    task: (Chunk) -> T,
): List<T>
