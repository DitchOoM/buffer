package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal fun bytesOf(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }

internal fun PlatformBuffer.remainingBytes(): ByteArray = ByteArray(remaining()) { readByte() }

class ChannelBridgeTest {
    @Test
    fun readRemainingBuffer_roundTrip() =
        runTest {
            for (size in intArrayOf(0, 1, 8, 4096, 20000)) {
                val bytes = bytesOf(size)
                val buffer = ByteReadChannel(bytes).readRemainingBuffer()
                assertContentEquals(bytes, buffer.remainingBytes(), "size=$size")
            }
        }

    @Test
    fun writeBuffer_thenRead_roundTrip() =
        runTest {
            val payload = bytesOf(256)
            val src = BufferFactory.Default.allocate(payload.size)
            src.writeBytes(payload)
            src.resetForRead()

            val channel = ByteChannel(autoFlush = true)
            channel.writeBuffer(src)
            channel.flushAndClose()

            val out = channel.readRemainingBuffer()
            assertContentEquals(payload, out.remainingBytes())
        }

    @Test
    fun writeBuffer_doesNotConsumeSourcePosition() =
        runTest {
            val src = BufferFactory.managed().allocate(64)
            src.writeBytes(bytesOf(64))
            src.resetForRead()
            val before = src.position()

            val channel = ByteChannel(autoFlush = true)
            channel.writeBuffer(src)
            channel.flushAndClose()

            assertEquals(before, src.position(), "writeBuffer must not advance the source position")
        }

    @Test
    fun writeBuffer_throughPooledBuffer() =
        runTest {
            val pool = BufferPool(defaultBufferSize = 128, factory = BufferFactory.managed())
            val pooled = pool.acquire(64)
            val payload = bytesOf(64)
            pooled.writeBytes(payload)
            pooled.resetForRead()

            val channel = ByteChannel(autoFlush = true)
            channel.writeBuffer(pooled)
            channel.flushAndClose()

            assertContentEquals(payload, channel.readRemainingBuffer().remainingBytes())
            pool.release(pooled)
            pool.clear()
        }
}
