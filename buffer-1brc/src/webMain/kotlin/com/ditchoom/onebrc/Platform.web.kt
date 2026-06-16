package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed

/**
 * JS/WASM [MappedFile]: no mmap available, so the file is read once (as UTF-8 text via node `fs`)
 * and staged into a [PlatformBuffer]. Regions are copies of sub-ranges — fine for the small datasets
 * these single-threaded runtimes handle; the showcase's big runs are JVM/Native.
 */
private class WebMappedFile(
    content: String,
) : MappedFile {
    private val buffer: PlatformBuffer
    override val size: Long

    init {
        // content.length is UTF-16 units; *4 over-allocates enough for any UTF-8 encoding.
        // managed() = GC heap; Default on WASM is a bounded 256MB bump allocator that never frees.
        val staging = BufferFactory.managed().allocate(content.length * 4 + 8)
        staging.writeString(content)
        size = staging.position().toLong()
        buffer = staging
    }

    override fun region(
        offset: Long,
        length: Int,
    ): PlatformBuffer {
        val region = BufferFactory.managed().allocate(length)
        val base = offset.toInt()
        var i = 0
        while (i < length) {
            region[i] = buffer[base + i]
            i++
        }
        return region
    }

    override fun byteAt(offset: Long): Byte = buffer[offset.toInt()]

    override fun close() {}
}

actual fun openMappedFile(path: String): MappedFile = WebMappedFile(nodeReadFileUtf8(path))

actual fun defaultParallelism(): Int = 1

actual fun onebrcDefaultFactory(): BufferFactory = BufferFactory.managed()

actual fun <T> runChunks(
    chunks: List<Chunk>,
    task: (Chunk) -> T,
): List<T> = chunks.map { task(it) }
