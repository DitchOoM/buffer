package com.ditchoom.onebrc

import com.ditchoom.buffer.DirectJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * JVM [MappedFile] backed by FileChannel.map. Regions are wrapped zero-copy as [DirectJvmBuffer]
 * (a direct ByteBuffer over the mapping). Files <= 2GB are mapped once and sliced; larger files are
 * mapped per chunk (each chunk is < 2GB by construction, so no single mapping ever exceeds the
 * ByteBuffer Int-index limit).
 */
private class JvmMappedFile(
    private val raf: RandomAccessFile,
) : MappedFile {
    private val channel: FileChannel = raf.channel
    override val size: Long = channel.size()

    private val whole: MappedByteBuffer? =
        if (size in 1..Int.MAX_VALUE.toLong()) {
            channel.map(FileChannel.MapMode.READ_ONLY, 0, size).also { it.order(ByteOrder.BIG_ENDIAN) }
        } else {
            null
        }

    override fun region(
        offset: Long,
        length: Int,
    ): PlatformBuffer {
        val bb: ByteBuffer =
            if (whole != null) {
                val dup = whole.duplicate()
                dup.position(offset.toInt())
                dup.limit(offset.toInt() + length)
                dup.slice().order(ByteOrder.BIG_ENDIAN)
            } else {
                channel
                    .map(FileChannel.MapMode.READ_ONLY, offset, length.toLong())
                    .also { it.order(ByteOrder.BIG_ENDIAN) }
            }
        return DirectJvmBuffer(bb)
    }

    override fun byteAt(offset: Long): Byte =
        whole?.get(offset.toInt())
            ?: channel.map(FileChannel.MapMode.READ_ONLY, offset, 1).get(0)

    override fun close() {
        channel.close()
        raf.close()
    }
}

actual fun openMappedFile(path: String): MappedFile = JvmMappedFile(RandomAccessFile(path, "r"))
