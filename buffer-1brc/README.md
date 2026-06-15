# buffer-1brc — The One Billion Row Challenge, on ByteBuffer

A showcase that implements the [One Billion Row Challenge](https://github.com/gunnarmorling/1brc)
(1BRC) on top of the core `:buffer` primitives, to demonstrate that the library is fast *out of the
box*. The challenge: read a ~13 GB text file of one billion `Station;Temperature` lines and compute
the **min / mean / max** per station, sorted by name.

## Result

Measured on a 24-core WSL2 Linux box, JVM 21 / Kotlin/Native (release), warm page cache:

| Platform            | Dataset            | Size      | Workers | Wall clock | Throughput   |
|---------------------|--------------------|-----------|---------|------------|--------------|
| JVM                 | 1,000,000,000 rows | 13.25 GB  | 24      | **2.59 s** | 5,110 MB/s   |
| JVM                 | 100,000,000 rows   | 1.32 GB   | 24      | 0.63 s     | 2,111 MB/s   |

### Cross-platform (kotlinx-benchmark / JMH)

Identical **1,000,000-row** dataset (seed 1) on every platform; AverageTime, 3 warmups + 5 iterations.
Run with `./gradlew :buffer-1brc:<target>FullBenchmark` (targets: `jvmBenchmark`, `linuxX64Benchmark`,
`jsBenchmark`, `wasmJsBenchmark`).

| Platform           | Workers | Score (ms/op)   | ≈ Throughput   |
|--------------------|---------|-----------------|----------------|
| JVM                | 24      | 5.02 ± 0.18     | ~199 M rows/s  |
| Native linuxX64    | 24      | 10.81 ± 0.13    | ~92 M rows/s   |
| WASM (node)        | 1       | 220.3 ± 2.2     | ~4.5 M rows/s  |
| JS (node)          | 1       | 911.7 ± 4.8     | ~1.1 M rows/s  |

JVM/Native run 24 worker threads; JS/WASM are single-threaded (no threads in those runtimes), so part
of the gap is parallelism, not raw speed.

Reproduce the headline large-scale runs:
- JVM: `./gradlew :buffer-1brc:onebrcRun -Ponebrc.rows=1000000000`
- Native: `./gradlew :buffer-1brc:linkReleaseExecutableLinuxX64` then
  `build/bin/linuxX64/releaseExecutable/buffer-1brc.kexe --file <measurements.txt>`

## How it maps to the library

The hot path is built entirely from `:buffer` primitives — no hand-rolled byte twiddling:

| 1BRC stage              | Primitive used                                                              |
|-------------------------|------------------------------------------------------------------------------|
| Find `;` / `\n`         | `ReadBuffer.indexOf(byte)` — SIMD/8-byte bulk scan                            |
| Parse `-12.3`           | `ReadBuffer.readFixedDecimalTenths(offset, length)` — allocation-free        |
| Key the station map     | `ReadBuffer.hashRange(offset, length)` (FNV-1a, 8-byte bulk)                  |
| Resolve hash collisions | `ReadBuffer.regionEquals(...)` — exact byte compare against the key arena     |
| Zero-copy file access   | mmap → `DirectJvmBuffer` (JVM) / `wrapNativeAddress` (Native)                 |
| Aggregation table       | `androidx.collection.MutableLongObjectMap` (Romain Guy's Swiss tables)        |

`readFixedDecimalTenths`, `hashRange`, and `regionEquals` were added to the core library *because*
the challenge exposed them as missing — they're reusable by any text/binary protocol, not 1BRC-specific.

## Design notes

- **No primitive arrays.** Per the project rule, there are no `ByteArray`/`IntArray`/`LongArray`
  data structures. The Swiss-table map owns its slots; station names are copied **once** into a
  deterministic-memory `KeyArena` (`PlatformBuffer`); `Stats` holds only scalar fields.
- **Zero per-row allocation.** Each row is scanned, parsed, hashed, and merged without allocating.
- **Parallelism.** `ChunkSplitter` cuts the file into newline-aligned chunks (one per core); each
  worker builds its own `StationTable`; a final sequential pass merges them. No locks.
- **Deterministic memory.** The key arena uses `BufferFactory.deterministic()` (native, explicitly
  freed) — zero GC pressure on the hot path.

## Tests & benchmark are shared

There is **one** end-to-end test (`commonTest/OneBrcTest`) and **one** benchmark
(`commonBenchmark/OneBrcBenchmark`), both written once in common code and run on every platform via
`expect`/`actual` file helpers + the cross-platform `kotlinx.benchmark` annotations. The same exact
assertions (and an independent in-test reference) run on JVM and Native — no per-platform copies.

```bash
./gradlew :buffer-1brc:jvmTest          # 8 tests
./gradlew :buffer-1brc:linuxX64Test     # the same 8 tests, native
./gradlew :buffer-1brc:jsNodeTest       # the same 8 tests, JS (node)
./gradlew :buffer-1brc:wasmJsNodeTest   # the same 8 tests, WASM (node)
```

## Platform status

- **JVM** — complete and benchmarked (the canonical 1BRC arena).
- **Native linuxX64** — complete and benchmarked. posix `mmap` → `wrapNativeAddress` (zero-copy, no
  custom cinterop — Kotlin/Native's posix bindings suffice), parallel via coroutines
  `Dispatchers.Default`. Output byte-identical to JVM.
- **Native linuxArm64** — compiles from the identical shared `nativeMain` code (cross-compiled here;
  runs on arm64 hardware/CI).
- **Apple (macОS/iOS)** — targets declared (macOS-only build); reuse the same `nativeMain` posix-mmap
  code verbatim. Builds on a Mac / CI, not on this Linux host. See the Apple note below.
- **JS / WASM** — complete and tested on node. No mmap, so the file is read via node `fs` into a
  `PlatformBuffer` (`writeString`), single-threaded `runChunks`. JS and WASM share one `webMain`
  source set via the `= js("…")` interop. The same 8 tests pass on both.

`androidx.collection` 1.6.0 publishes for every target above.

### Apple: mmap vs NSData

Apple has posix `mmap`, so it reuses the Linux code with zero changes — chosen here for maximum
shared, already-verified code. A more idiomatic alternative is
`NSData(contentsOfFile:, NSDataReadingMappedIfSafe)` → the library's zero-copy `wrap(nsData)`, which
is also mmap underneath but integrates with the Apple NSData interop; it's a drop-in swap for
`openMappedFile`'s Apple actual if preferred.

## Running

```bash
# Quick local run (generates a 10M-row temp file)
./gradlew :buffer-1brc:onebrcRun

# Full challenge (generates ~13 GB; needs disk + a couple minutes to generate)
./gradlew :buffer-1brc:onebrcRun -Ponebrc.rows=1000000000 -Ponebrc.workers=24

# Against an existing file
./gradlew :buffer-1brc:onebrcRun -Ponebrc.file=/path/to/measurements.txt

# As a formal kotlinx-benchmark (@Benchmark in src/jvmBenchmark)
./gradlew :buffer-1brc:jvmBenchmarkBenchmark
```

Correctness is covered by `OneBrcJvmTest` (golden diff against an independent JDK reference, single-
and multi-threaded) and `ChunkSplitterTest` (boundary/partition invariants).
