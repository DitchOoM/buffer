package com.ditchoom.onebrc

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
