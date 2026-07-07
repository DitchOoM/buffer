package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import okio.Buffer
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Test group B: where the contract promises a copy, mutate the source after the conversion and
 * assert the destination is unchanged. All bridge directions are covered.
 */
class CopyVsAliasTest {
    @Test
    fun asOkioSource_producesIndependentBytes() {
        val original = patternBytes(64)
        val buffer = readableBufferOf(original.copyOf(), BufferFactory.managed())
        val out = buffer.asOkioSource().buffer().readByteArray()

        // Mutate the source's backing bytes after the read; the already-copied output must not move.
        for (i in 0 until 64) buffer[i] = 0xFF.toByte()
        assertContentEquals(original, out, "asOkioSource output must be independent of the source")
    }

    @Test
    fun asOkioSink_producesIndependentBytes() {
        val arr = patternBytes(64)
        val src = Buffer().apply { write(arr) }
        val dst = BufferFactory.managed().allocate(64)
        dst.asOkioSink().write(src, src.size)

        // Mutate the original array feeding the source; dst already owns its own copy.
        for (i in arr.indices) arr[i] = 0
        dst.resetForRead()
        assertContentEquals(patternBytes(64), ByteArray(64) { dst.readByte() }, "asOkioSink output must be independent")
    }

    @Test
    fun copyToOkioBuffer_producesIndependentBytes() {
        val original = patternBytes(64)
        val buffer = readableBufferOf(original.copyOf(), BufferFactory.managed())
        val out = buffer.copyToOkioBuffer()

        for (i in 0 until 64) buffer[i] = 0.toByte()
        assertContentEquals(original, out.readByteArray(), "copyToOkioBuffer must snapshot, not alias")
    }

    @Test
    fun copyToPlatformBuffer_producesIndependentBytes() {
        val original = patternBytes(64)
        val okioBuffer = Buffer().apply { write(original) }
        val platform = okioBuffer.copyToPlatformBuffer(BufferFactory.managed())

        okioBuffer.clear() // wipe the Okio source
        assertContentEquals(
            original,
            ByteArray(64) { platform.readByte() },
            "copyToPlatformBuffer must not alias the Okio Buffer",
        )
    }

    @Test
    fun copyToByteString_producesIndependentBytes() {
        val original = patternBytes(64)
        val buffer = readableBufferOf(original.copyOf(), BufferFactory.managed())
        val byteString = buffer.copyToByteString()

        for (i in 0 until 64) buffer[i] = 0.toByte()
        assertContentEquals(original, byteString.toByteArray(), "copyToByteString must snapshot, not alias")
    }

    // --- Native/direct backing: the assertions above only exercise BufferFactory.managed()
    // (ManagedMemoryAccess). BufferFactory.Default is native on JVM/Apple/JS; mirror each
    // assertion against that backing so the NativeMemoryAccess copy paths are pinned too.
    // Skips gracefully wherever Default falls back to a managed backing (e.g. Linux).

    @Test
    fun asOkioSource_producesIndependentBytes_nativeBacking() {
        val original = patternBytes(64)
        val buffer = nativeReadableBufferOrNull(original.copyOf()) ?: return
        val out = buffer.asOkioSource().buffer().readByteArray()

        // Mutate the source's backing bytes after the read; the already-copied output must not move.
        for (i in 0 until 64) buffer[i] = 0xFF.toByte()
        assertContentEquals(original, out, "asOkioSource output must be independent of a native source")
    }

    @Test
    fun asOkioSink_producesIndependentBytes_nativeBacking() {
        val arr = patternBytes(64)
        val src = Buffer().apply { write(arr) }
        val dst = nativeAllocateOrNull(64) ?: return
        dst.asOkioSink().write(src, src.size)

        // Mutate the original array feeding the source; dst already owns its own copy.
        for (i in arr.indices) arr[i] = 0
        dst.resetForRead()
        assertContentEquals(
            patternBytes(64),
            ByteArray(64) { dst.readByte() },
            "asOkioSink output must be independent of the source array (native destination)",
        )
    }

    @Test
    fun copyToOkioBuffer_producesIndependentBytes_nativeBacking() {
        val original = patternBytes(64)
        val buffer = nativeReadableBufferOrNull(original.copyOf()) ?: return
        val out = buffer.copyToOkioBuffer()

        for (i in 0 until 64) buffer[i] = 0.toByte()
        assertContentEquals(original, out.readByteArray(), "copyToOkioBuffer must snapshot a native source, not alias")
    }

    @Test
    fun copyToPlatformBuffer_producesIndependentBytes_nativeBacking() {
        if (nativeAllocateOrNull(1) == null) return
        val original = patternBytes(64)
        val okioBuffer = Buffer().apply { write(original) }
        val platform = okioBuffer.copyToPlatformBuffer(BufferFactory.Default)

        okioBuffer.clear() // wipe the Okio source
        assertContentEquals(
            original,
            ByteArray(64) { platform.readByte() },
            "copyToPlatformBuffer must not alias the Okio Buffer when the destination is native",
        )
    }

    @Test
    fun copyToByteString_producesIndependentBytes_nativeBacking() {
        val original = patternBytes(64)
        val buffer = nativeReadableBufferOrNull(original.copyOf()) ?: return
        val byteString = buffer.copyToByteString()

        for (i in 0 until 64) buffer[i] = 0.toByte()
        assertContentEquals(
            original,
            byteString.toByteArray(),
            "copyToByteString must snapshot a native source, not alias",
        )
    }
}
