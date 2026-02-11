@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package com.ditchoom.buffer

import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_convert_utf16le_to_utf8
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * Benchmarks different writeString implementations on Linux to validate
 * that simdutf UTF-16→UTF-8 conversion is faster than the per-char loop.
 *
 * Run with:
 *   cd /home/rbehera/git/buffer && ./gradlew :buffer:linuxX64Test --tests "*.WriteStringBenchmarkTest"
 */
class WriteStringBenchmarkTest {
    @Test
    fun benchmarkWriteStringApproaches() {
        val sizes =
            listOf(
                64 * 1024, // 64KB
                256 * 1024, // 256KB
                1024 * 1024, // 1MB
                4 * 1024 * 1024, // 4MB
                16 * 1024 * 1024, // 16MB
            )

        for (size in sizes) {
            val label =
                when {
                    size >= 1024 * 1024 -> "${size / 1024 / 1024}MB"
                    else -> "${size / 1024}KB"
                }

            val text = buildString(size) { repeat(size) { append('A' + (it % 26)) } }

            // Warmup all paths
            repeat(2) {
                val buf = PlatformBuffer.allocate(size * 3, AllocationZone.Direct) as NativeBuffer
                writeStringCurrentLoop(buf, text)
                buf.freeNativeMemory()

                val buf2 = PlatformBuffer.allocate(size * 3, AllocationZone.Direct) as NativeBuffer
                writeStringEncodeToByteArray(buf2, text)
                buf2.freeNativeMemory()

                val buf3 = PlatformBuffer.allocate(size * 3, AllocationZone.Direct) as NativeBuffer
                writeStringSimdutf(buf3, text)
                buf3.freeNativeMemory()
            }

            // Benchmark 1: Current per-char loop
            val buf1 = PlatformBuffer.allocate(size * 3, AllocationZone.Direct) as NativeBuffer
            val mark1 = TimeSource.Monotonic.markNow()
            writeStringCurrentLoop(buf1, text)
            val time1 = mark1.elapsedNow()
            val written1 = buf1.position()
            buf1.freeNativeMemory()

            // Benchmark 2: encodeToByteArray + writeBytes
            val buf2 = PlatformBuffer.allocate(size * 3, AllocationZone.Direct) as NativeBuffer
            val mark2 = TimeSource.Monotonic.markNow()
            writeStringEncodeToByteArray(buf2, text)
            val time2 = mark2.elapsedNow()
            val written2 = buf2.position()
            buf2.freeNativeMemory()

            // Benchmark 3: simdutf (toCharArray + pin + convert)
            val buf3 = PlatformBuffer.allocate(size * 3, AllocationZone.Direct) as NativeBuffer
            val mark3 = TimeSource.Monotonic.markNow()
            writeStringSimdutf(buf3, text)
            val time3 = mark3.elapsedNow()
            val written3 = buf3.position()
            buf3.freeNativeMemory()

            // Benchmark 4: simdutf from encodeToByteArray chars (avoid toCharArray)
            // Actually, let's measure toCharArray + pin separately
            val mark4a = TimeSource.Monotonic.markNow()
            val chars = text.toCharArray()
            val toCharArrayTime = mark4a.elapsedNow()

            val buf4 = PlatformBuffer.allocate(size * 3, AllocationZone.Direct) as NativeBuffer
            val mark4b = TimeSource.Monotonic.markNow()
            writeStringSimdutfFromCharArray(buf4, chars)
            val simdutfOnlyTime = mark4b.elapsedNow()
            val written4 = buf4.position()
            buf4.freeNativeMemory()

            assertEquals(written1, written2, "encodeToByteArray produced different byte count")
            assertEquals(written1, written3, "simdutf produced different byte count")
            assertEquals(written1, written4, "simdutf-from-chararray produced different byte count")

            println(
                "BENCH size=$label " +
                    "perChar=${time1.inWholeMilliseconds}ms " +
                    "encodeToByteArray=${time2.inWholeMilliseconds}ms " +
                    "simdutf(toCharArray+convert)=${time3.inWholeMilliseconds}ms " +
                    "toCharArray=${toCharArrayTime.inWholeMilliseconds}ms " +
                    "simdutfOnly=${simdutfOnlyTime.inWholeMilliseconds}ms " +
                    "speedup_encode=${if (time2.inWholeMicroseconds > 0) time1.inWholeMicroseconds / time2.inWholeMicroseconds else 0}x " +
                    "speedup_simdutf=${if (time3.inWholeMicroseconds > 0) time1.inWholeMicroseconds / time3.inWholeMicroseconds else 0}x",
            )
        }
    }

    @Test
    fun verifySimdutfCorrectness() {
        // Test with various Unicode content
        val testCases =
            listOf(
                "Hello, World!", // ASCII
                "Héllo Wörld", // Latin-1 extended
                "こんにちは世界", // Japanese (3-byte UTF-8)
                "Hello 🌍🌎🌏", // Emoji (4-byte UTF-8, surrogate pairs)
                "Mixed: café 日本語 🎉 résumé", // Mixed
                buildString { repeat(1000) { append('*') } }, // 1KB ASCII
            )

        for ((i, text) in testCases.withIndex()) {
            val expected = text.encodeToByteArray()

            val buf = PlatformBuffer.allocate(text.length * 4, AllocationZone.Direct) as NativeBuffer
            writeStringSimdutf(buf, text)
            buf.resetForRead()
            val actual = buf.readByteArray(buf.remaining())
            buf.freeNativeMemory()

            assertEquals(
                expected.toList(),
                actual.toList(),
                "simdutf mismatch for test case $i: ${text.take(30)}...",
            )
        }
        println("VERIFY: All simdutf correctness tests passed")
    }

    // --- Implementation candidates ---

    /** Current implementation: per-character loop */
    private fun writeStringCurrentLoop(
        buf: NativeBuffer,
        text: String,
    ) {
        buf.writeString(text, Charset.UTF8)
    }

    /** Option 1: encodeToByteArray + writeBytes */
    private fun writeStringEncodeToByteArray(
        buf: NativeBuffer,
        text: String,
    ) {
        val bytes = text.encodeToByteArray()
        buf.writeBytes(bytes)
    }

    /** Option 2: simdutf convert_utf16le_to_utf8 via toCharArray */
    private fun writeStringSimdutf(
        buf: NativeBuffer,
        text: String,
    ) {
        if (text.isEmpty()) return
        val chars = text.toCharArray()
        writeStringSimdutfFromCharArray(buf, chars)
    }

    /** simdutf conversion from pre-existing CharArray (to measure conversion-only time) */
    private fun writeStringSimdutfFromCharArray(
        buf: NativeBuffer,
        chars: CharArray,
    ) {
        if (chars.isEmpty()) return
        val dstAddr = buf.nativeAddress + buf.position()
        val written =
            chars.usePinned { pinned ->
                buf_simdutf_convert_utf16le_to_utf8(
                    pinned.addressOf(0).reinterpret(),
                    chars.size.convert(),
                    dstAddr.toCPointer()!!,
                ).toInt()
            }
        buf.position(buf.position() + written)
    }
}
