package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Test group B: where the contract promises a copy, mutate the source after the conversion and
 * assert the destination is unchanged. All four bridge directions are covered.
 */
class CopyVsAliasTest {
    @Test
    fun asRawSource_producesIndependentBytes() {
        val original = patternBytes(64)
        val buffer = readableBufferOf(original.copyOf(), BufferFactory.managed())
        val out = buffer.asRawSource().buffered().readByteArray()

        // Mutate the source's backing bytes after the read; the already-copied output must not move.
        for (i in 0 until 64) buffer[i] = 0xFF.toByte()
        assertContentEquals(original, out, "asRawSource output must be independent of the source")
    }

    @Test
    fun asRawSink_producesIndependentBytes() {
        val arr = patternBytes(64)
        val src = Buffer().apply { write(arr) }
        val dst = BufferFactory.managed().allocate(64)
        dst.asRawSink().write(src, src.size)

        // Mutate the original array feeding the source; dst already owns its own copy.
        for (i in arr.indices) arr[i] = 0
        dst.resetForRead()
        assertContentEquals(patternBytes(64), ByteArray(64) { dst.readByte() }, "asRawSink output must be independent")
    }

    @Test
    fun copyToKotlinxIoBuffer_producesIndependentBytes() {
        val original = patternBytes(64)
        val buffer = readableBufferOf(original.copyOf(), BufferFactory.managed())
        val out = buffer.copyToKotlinxIoBuffer()

        for (i in 0 until 64) buffer[i] = 0.toByte()
        assertContentEquals(original, out.readByteArray(), "copyToKotlinxIoBuffer must snapshot, not alias")
    }

    @Test
    fun copyToPlatformBuffer_producesIndependentBytes() {
        val original = patternBytes(64)
        val kx = Buffer().apply { write(original) }
        val platform = kx.copyToPlatformBuffer(BufferFactory.managed())

        kx.clear() // wipe the kotlinx-io source
        assertContentEquals(
            original,
            ByteArray(64) { platform.readByte() },
            "copyToPlatformBuffer must not alias the kotlinx-io Buffer",
        )
    }
}
