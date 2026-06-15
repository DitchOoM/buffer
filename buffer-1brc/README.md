# buffer-1brc — The One Billion Row Challenge, on ByteBuffer

A showcase that implements the [One Billion Row Challenge](https://github.com/gunnarmorling/1brc)
(1BRC) on top of the core `:buffer` primitives, to demonstrate that the library is fast *out of the
box*. The challenge: read a ~13 GB text file of one billion `Station;Temperature` lines and compute
the **min / mean / max** per station, sorted by name.

## Result

Measured on a 24-core WSL2 Linux box, JVM 21 / Kotlin/Native (release), warm page cache (warm
steady-state over `--repeat`):

| Platform            | Dataset            | Size      | Workers | Wall clock | Throughput   |
|---------------------|--------------------|-----------|---------|------------|--------------|
| JVM                 | 1,000,000,000 rows | 13.25 GB  | 24      | **~2.25 s**| ~5,900 MB/s  |
| Native linuxX64     | 1,000,000,000 rows | 13.25 GB  | 24      | **~4.0 s** | ~3,300 MB/s  |

### Cross-platform (kotlinx-benchmark / JMH)

Identical **1,000,000-row** dataset (seed 1) on every platform; AverageTime, 3 warmups + 5 iterations.
Run with `./gradlew :buffer-1brc:<target>FullBenchmark` (targets: `jvmBenchmark`, `linuxX64Benchmark`,
`jsBenchmark`, `wasmJsBenchmark`).

| Platform           | Workers | Score (ms/op)   | ≈ Throughput   |
|--------------------|---------|-----------------|----------------|
| JVM                | 24      | 4.46 ± 0.34     | ~224 M rows/s  |
| Native linuxX64    | 24      | 5.80 ± 0.07     | ~172 M rows/s  |
| WASM (node)        | 1       | 199.9 ± 3.0     | ~5.0 M rows/s  |
| JS (node)          | 1       | 556.2 ± 6.8     | ~1.8 M rows/s  |

JVM/Native run 24 worker threads; JS/WASM are single-threaded (no threads in those runtimes), so part
of the gap is parallelism, not raw speed.

> These numbers reflect the **check-once / unchecked-loop** optimization below. Before it, native was
> 10.81 ms/op (1M) and ~6.7 s (13 GB); profiling showed ~21% of native time in per-access bounds
> checks that the JVM's JIT hoists out of hot loops but Kotlin/Native cannot. See *Buffer backends &
> the JIT gap*.

Reproduce the headline large-scale runs:
- JVM: `./gradlew :buffer-1brc:onebrcRun -Ponebrc.rows=1000000000` (add `-Ponebrc.repeat=6` for warm
  steady-state, `-Ponebrc.managed=true` to scan a heap copy instead of the mmap)
- Native: `./gradlew :buffer-1brc:linkReleaseExecutableLinuxX64` then
  `build/bin/linuxX64/releaseExecutable/buffer-1brc.kexe --file <measurements.txt> --repeat 6`

### Buffer backends & the JIT gap

The same solver, scanning the **mmap region directly** (zero-copy `NativeBuffer` / `DirectJvmBuffer`,
the default) versus a **heap copy** (`BufferFactory.managed()` → `ByteArrayBuffer` / `HeapJvmBuffer`,
via `--managed`). 100M rows / 1.32 GB, warm steady-state:

| Backend                          | Native linuxX64 | JVM       |
|----------------------------------|-----------------|-----------|
| Default (mmap, zero-copy)        | ~0.43 s         | ~0.25 s   |
| Managed (heap copy)              | ~1.0 s          | ~0.30 s   |
| **Managed penalty**              | **~2.3×**       | **~1.2×** |

The backend choice costs far more without a JIT. On the JVM, HotSpot lowers `HeapJvmBuffer.getLong`
to a single intrinsic and elides the bounds checks, so heap vs direct is ~1.2×. On Kotlin/Native
there is no JIT: `ByteArrayBuffer.getLong` is assembled from 8 array reads + shifts, `indexOf` can't
use libc `memchr`, and the scan adds GC pressure — ~2.3×. At 13 GB the heap copy needs the whole file
in heap and OOMs a default `-Xmx` — **zero-copy mmap is required at scale.**

**Copy vs access path (native control).** `--deterministic` copies each chunk into an *off-heap*
`NativeBuffer` (same fast access path as the mmap, but paying a one-time copy) — a control that splits
the managed penalty into "the copy" vs "the buffer type":

| Native, 100M, warm        | Time     | Isolates                                   |
|---------------------------|----------|--------------------------------------------|
| Default (no copy)         | ~0.42 s  | —                                          |
| Deterministic (off-heap copy) | ~0.47 s  | + copy cost only (still `NativeBuffer`)    |
| Managed (heap copy)       | ~1.0 s   | + copy **and** `ByteArrayBuffer` access    |

So of the ~0.6 s managed overhead, the **copy is only ~0.05 s (~8%)** and the **`ByteArrayBuffer`
access path is ~0.56 s (~92%)**. It is *not* `ByteArray` pinning (reads use array indexing; pinning
only happens during the bulk `memcpy`, which the off-heap copy shows is cheap). And `Default` vs
`deterministic` access is identical — both are `NativeBuffer` — so the allocation *strategy* doesn't
move the scan; only the buffer *type* does.

### check-once / unchecked-loop

To recover what the JIT does for free, the bulk read primitives (`hashRange`, `regionEquals`,
`readFixedDecimalTenths`, `contentEquals`, `mismatch`) validate the whole accessed range **once**, then
loop over internal unchecked accessors (`ReadBuffer.getUnchecked` / `getLongUnchecked`). The default
implementations delegate to the checked accessors (JVM unchanged — the JIT already eliminates the
per-element checks); non-JIT backends (`NativeBuffer`, `ByteArrayBuffer`, wasm `LinearBuffer`, JS,
Apple `MutableDataBuffer`) override them to skip the per-element check. Safety is preserved — every
public call still validates the full range — it just stops repeating the check the JIT would have
hoisted. This is a core `:buffer` win for every non-JIT consumer, not 1BRC-specific.

Two further refinements: `bufferHashCode` and the `contentEquals`/`regionEquals` tail were converted
to 8-byte bulk loads (one `getLong` per word, an overlapping final word instead of a per-byte tail),
and on native direct buffers (`NativeBuffer` on linux, `MutableDataBuffer` on apple) `hashRange` is
implemented as a single cinterop C call (`buf_fnv1a_64`, shared via `nativeFnv1aHashRange` in
`nativeMain`) — the whole FNV digest runs in C with raw pointer arithmetic, so the per-element
`CPointer` materialization disappears. (The native/wasm heap `ByteArrayBuffer` keeps the Kotlin loop.)
Profiling guided this precisely: `indexOf` (already libc `memchr`) measured even between C and an
inline Kotlin SWAR scan, so it was left alone; `hashRange`, which had a Kotlin per-byte tail that
couldn't be hoisted, is the case where dropping into C actually paid off (~5% on native).

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
