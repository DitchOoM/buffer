package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory

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
