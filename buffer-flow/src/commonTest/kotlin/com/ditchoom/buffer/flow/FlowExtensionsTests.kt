package com.ditchoom.buffer.flow

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowExtensionsTests {
    // --- lines() tests ---

    @Test
    fun linesSingleChunkMultipleLines() =
        runTest {
            val result = flowOf("a\nb\nc\n").lines().toList()
            assertEquals(listOf("a", "b", "c"), result)
        }

    @Test
    fun linesSplitAcrossChunks() =
        runTest {
            val result = flowOf("hel", "lo\nwor", "ld\n").lines().toList()
            assertEquals(listOf("hello", "world"), result)
        }

    @Test
    fun linesEmptyLinesPreserved() =
        runTest {
            val result = flowOf("a\n\nb\n").lines().toList()
            assertEquals(listOf("a", "", "b"), result)
        }

    @Test
    fun linesCrLfHandling() =
        runTest {
            val result = flowOf("a\r\nb\r\n").lines().toList()
            assertEquals(listOf("a", "b"), result)
        }

    @Test
    fun linesCrLfSplitAcrossChunks() =
        runTest {
            val result = flowOf("a\r", "\nb\n").lines().toList()
            assertEquals(listOf("a", "b"), result)
        }

    @Test
    fun linesTrailingContentWithoutNewline() =
        runTest {
            val result = flowOf("a\nb").lines().toList()
            assertEquals(listOf("a", "b"), result)
        }

    @Test
    fun linesEmptyFlow() =
        runTest {
            val result = emptyFlow<String>().lines().toList()
            assertEquals(emptyList(), result)
        }

    @Test
    fun linesSingleLineNoNewline() =
        runTest {
            val result = flowOf("hello").lines().toList()
            assertEquals(listOf("hello"), result)
        }

    @Test
    fun linesOnlyNewlines() =
        runTest {
            val result = flowOf("\n\n\n").lines().toList()
            assertEquals(listOf("", "", ""), result)
        }

    // --- mapBuffer() tests ---

    @Test
    fun mapBufferIdentityPreservesData() =
        runTest {
            val buf = PlatformBuffer.allocate(16)
            buf.writeInt(42)
            buf.resetForRead()

            val result = flowOf(buf).mapBuffer { it }.toList()
            assertEquals(1, result.size)
            assertEquals(42, result[0].readInt())
        }

    @Test
    fun mapBufferTransformApplied() =
        runTest {
            val buf = PlatformBuffer.allocate(16)
            buf.writeString("hello")
            buf.resetForRead()

            val result =
                flowOf(buf)
                    .mapBuffer { original ->
                        val text = original.readString(original.remaining())
                        val newBuf = PlatformBuffer.allocate(32)
                        newBuf.writeString(text.uppercase())
                        newBuf.resetForRead()
                        newBuf
                    }.toList()

            assertEquals(1, result.size)
            assertEquals("HELLO", result[0].readString(result[0].remaining()))
        }

    // --- asStringFlow() tests ---

    @Test
    fun asStringFlowConvertsBuffers() =
        runTest {
            val buf1 = PlatformBuffer.allocate(16)
            buf1.writeString("hello")
            buf1.resetForRead()
            val buf2 = PlatformBuffer.allocate(16)
            buf2.writeString(" world")
            buf2.resetForRead()

            val result = flowOf(buf1, buf2).asStringFlow().toList()
            assertEquals(listOf("hello", " world"), result)
        }

    @Test
    fun asStringFlowReadsFromCurrentPosition() =
        runTest {
            val buf = PlatformBuffer.allocate(16)
            buf.writeString("test")
            buf.resetForRead()

            val result = flowOf(buf).asStringFlow().toList()
            assertEquals(listOf("test"), result)
        }

    // --- Composition tests ---

    @Test
    fun mapBufferToStringFlowToLines() =
        runTest {
            val buf1 = PlatformBuffer.allocate(32)
            buf1.writeString("hello\nwor")
            buf1.resetForRead()
            val buf2 = PlatformBuffer.allocate(32)
            buf2.writeString("ld\n")
            buf2.resetForRead()

            val result =
                flowOf(buf1, buf2)
                    .mapBuffer { it }
                    .asStringFlow()
                    .lines()
                    .toList()

            assertEquals(listOf("hello", "world"), result)
        }
}
