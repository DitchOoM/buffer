@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.wrapNativeAddress
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import platform.posix.MAP_PRIVATE
import platform.posix.O_RDONLY
import platform.posix.PROT_READ
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.lseek
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.open
import platform.posix.sysconf
import platform.posix.close as posixClose

/**
 * Linux [MappedFile] backed by posix `mmap` (no custom cinterop needed — Kotlin/Native ships the
 * posix bindings). Each region is a zero-copy view wrapped via [PlatformBuffer.wrapNativeAddress],
 * which on Linux delegates to `NativeBuffer.wrapExternal` over the mapping address.
 */
private class LinuxMappedFile(
    path: String,
) : MappedFile {
    private val fd: Int = open(path, O_RDONLY)
    override val size: Long
    private val baseAddress: Long

    init {
        check(fd >= 0) { "open() failed for $path" }
        size = lseek(fd, 0L, SEEK_END)
        lseek(fd, 0L, SEEK_SET)
        val mapped = mmap(null, size.convert(), PROT_READ, MAP_PRIVATE, fd, 0L)
        baseAddress = mapped?.toLong() ?: -1L
        // mmap returns MAP_FAILED ((void*)-1) on error, never a non-trivial null.
        check(baseAddress != -1L && baseAddress != 0L) { "mmap() failed for $path" }
    }

    override fun region(
        offset: Long,
        length: Int,
    ): PlatformBuffer = PlatformBuffer.wrapNativeAddress(baseAddress + offset, length, ByteOrder.BIG_ENDIAN)

    override fun byteAt(offset: Long): Byte = (baseAddress + offset).toCPointer<ByteVar>()!!.pointed.value

    override fun close() {
        munmap(baseAddress.toCPointer<ByteVar>(), size.convert())
        posixClose(fd)
    }
}

actual fun openMappedFile(path: String): MappedFile = LinuxMappedFile(path)

actual fun defaultParallelism(): Int {
    val online = sysconf(_SC_NPROCESSORS_ONLN)
    return if (online > 0) online.toInt() else 1
}

actual fun onebrcDefaultFactory(): BufferFactory = BufferFactory.deterministic()

/**
 * Parallel execution across CPU cores via coroutines [Dispatchers.Default] (multi-threaded under
 * Kotlin/Native's memory manager). Each chunk gets its own [StationTable] on a worker thread; the
 * read-only mmap is shared, and per-worker tables are merged by the caller. No shared mutable state.
 */
actual fun <T> runChunks(
    chunks: List<Chunk>,
    task: (Chunk) -> T,
): List<T> =
    if (chunks.size <= 1) {
        chunks.map { task(it) }
    } else {
        runBlocking {
            chunks
                .map { chunk -> async(Dispatchers.Default) { task(chunk) } }
                .awaitAll()
        }
    }
