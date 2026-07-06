package com.ditchoom.buffer.okio.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.okio.asOkioSink
import com.ditchoom.buffer.okio.asOkioSource
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import okio.Buffer

/**
 * Bridge benchmarks: PlatformBuffer <-> Okio [Buffer] copy throughput.
 *
 * The matrix is `operation x backing x size`:
 * - operation: source (PlatformBuffer -> okio.Buffer via [asOkioSource]) and
 *   sink (okio.Buffer -> PlatformBuffer via [asOkioSink]).
 * - backing: managed ([BufferFactory.managed], ManagedMemoryAccess) and direct
 *   ([BufferFactory.Default], native memory on JVM/native).
 * - size: 1 KiB, 64 KiB, 1 MiB (via the [size] `@Param`).
 *
 * For each direction three variants are measured:
 * - `*Bridge`   — the shipping single-copy path (Buffer.UnsafeCursor).
 * - `*ScratchOld` — a two-copy path (native memory -> scratch ByteArray -> Okio segment),
 *   reproduced here ONLY so the single-copy path can be measured against a naive baseline.
 * - `baselineBufferCopy` — plain `dst.write(source)` PlatformBuffer-to-PlatformBuffer
 *   copy, the one-memcpy reference.
 *
 * The sink variants additionally pay for one `Buffer.write(payload)` refill per
 * invocation (the Okio source is consumed by `write`); that cost is identical for
 * `sinkBridge` and `sinkScratchOld`, so their relative comparison stays fair.
 *
 * Run: `./gradlew jvmBenchmarkBenchmark` / `macosArm64BenchmarkBenchmark`
 * (add `-Pbenchmark.filter=Bridge` to isolate; `Quick` config for a fast pass).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class BridgeBenchmark {
    @Param("1024", "65536", "1048576")
    var size: Int = 0

    @Param("managed", "direct")
    var backing: String = ""

    private lateinit var payload: ByteArray
    private lateinit var scratch: ByteArray
    private lateinit var platformSrc: PlatformBuffer
    private lateinit var platformDst: PlatformBuffer
    private lateinit var baselineDst: PlatformBuffer
    private lateinit var reuseOkio: Buffer

    @Setup
    fun setup() {
        val factory = if (backing == "managed") BufferFactory.managed() else BufferFactory.Default
        payload = ByteArray(size) { ((it * PATTERN_MULTIPLIER + PATTERN_OFFSET) and BYTE_MASK).toByte() }
        platformSrc =
            factory.allocate(size).apply {
                writeBytes(payload)
                resetForRead()
            }
        platformDst = factory.allocate(size)
        baselineDst = factory.allocate(size)
        scratch = ByteArray(SCRATCH_CHUNK)
        reuseOkio = Buffer()
    }

    // ---- PlatformBuffer -> okio.Buffer ----

    @Benchmark
    fun sourceBridge(): Long {
        platformSrc.position(0)
        reuseOkio.clear()
        val src = platformSrc.asOkioSource()
        var total = 0L
        while (true) {
            val r = src.read(reuseOkio, size.toLong())
            if (r < 0L) break
            total += r
        }
        return total
    }

    @Benchmark
    fun sourceScratchOld(): Long {
        platformSrc.position(0)
        reuseOkio.clear()
        oldSourceCopy(platformSrc, reuseOkio, size, scratch)
        return reuseOkio.size
    }

    // ---- okio.Buffer -> PlatformBuffer ----

    @Benchmark
    fun sinkBridge(): Int {
        platformDst.position(0)
        platformDst.setLimit(size)
        reuseOkio.clear()
        reuseOkio.write(payload)
        platformDst.asOkioSink().write(reuseOkio, reuseOkio.size)
        return platformDst.position()
    }

    @Benchmark
    fun sinkScratchOld(): Int {
        platformDst.position(0)
        platformDst.setLimit(size)
        reuseOkio.clear()
        reuseOkio.write(payload)
        oldSinkCopy(platformDst, reuseOkio, reuseOkio.size, scratch)
        return platformDst.position()
    }

    // ---- Baseline: one-memcpy PlatformBuffer -> PlatformBuffer ----

    @Benchmark
    fun baselineBufferCopy(): Int {
        platformSrc.position(0)
        baselineDst.position(0)
        baselineDst.setLimit(size)
        baselineDst.write(platformSrc)
        return baselineDst.position()
    }

    private companion object {
        private const val SCRATCH_CHUNK = 8192

        // Deterministic, position-dependent fill pattern (misordered copies produce mismatches).
        private const val PATTERN_MULTIPLIER = 31
        private const val PATTERN_OFFSET = 7
        private const val BYTE_MASK = 0xFF

        /**
         * Reproduces the retired source-side path: managed backings did one direct
         * `Buffer.write(backingArray, ...)`; native backings staged through a scratch array
         * (native memory -> scratch -> Okio segment = two copies).
         */
        private fun oldSourceCopy(
            source: ReadBuffer,
            sink: Buffer,
            n: Int,
            scratch: ByteArray,
        ) {
            val pos = source.position()
            val mma = source.managedMemoryAccess
            if (mma != null) {
                val start = mma.arrayOffset + pos
                sink.write(mma.backingArray, start, n)
                source.position(pos + n)
            } else {
                var remaining = n
                while (remaining > 0) {
                    val chunk = minOf(remaining, scratch.size)
                    source.readInto(scratch, 0, chunk)
                    sink.write(scratch, 0, chunk)
                    remaining -= chunk
                }
            }
        }

        /**
         * Reproduces the retired sink-side path: managed backings read directly into the
         * backing array; native backings staged through a scratch array (two copies).
         */
        private fun oldSinkCopy(
            dst: WriteBuffer,
            source: Buffer,
            byteCount: Long,
            scratch: ByteArray,
        ) {
            var remaining = byteCount
            while (remaining > 0L) {
                val pos = dst.position()
                val mma = dst.managedMemoryAccess
                val read =
                    if (mma != null) {
                        val cap = dst.limit() - pos
                        val n = minOf(remaining, cap.toLong()).toInt()
                        val start = mma.arrayOffset + pos
                        val r = source.read(mma.backingArray, start, n)
                        if (r > 0) dst.position(pos + r)
                        r
                    } else {
                        val n = minOf(remaining, scratch.size.toLong()).toInt()
                        val r = source.read(scratch, 0, n)
                        if (r > 0) dst.writeBytes(scratch, 0, r)
                        r
                    }
                if (read <= 0) break
                remaining -= read.toLong()
            }
        }
    }
}
