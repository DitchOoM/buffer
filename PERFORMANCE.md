# Performance Guide

This document provides benchmark results and optimization guidelines for the buffer library across all supported platforms.

## Benchmark Results

Benchmarks run on:
- **JVM/JS/WasmJS/Native**: MacBook Air M2, 16GB RAM, JDK 21
- **JavaScript**: Node.js v22
- **WasmJS**: Node.js v22 with WASM runtime
- **Native**: macOS ARM64 (Apple Silicon)
- **Android**: Realme device

### Platform Comparison

| Benchmark | JVM | JS | WasmJS | macOS ARM64 |
|-----------|-----|-----|--------|-------------|
| Heap Allocation | 7.6M ops/s | 5.3M ops/s | 21.3M ops/s | 3.2M ops/s |
| Direct Allocation | 1.3M ops/s | 5.3M ops/s | 21.7M ops/s | 3.2M ops/s |
| Bulk Operations (Direct) | 56.3M ops/s | 10.3M ops/s | 13.6M ops/s | 17.2M ops/s |
| Bulk Operations (Heap) | 46.1M ops/s | 10.5M ops/s | 12.6M ops/s | 17.5M ops/s |
| Read/Write Int (Direct) | 4.4M ops/s | 36K ops/s | 111K ops/s | 85K ops/s |
| Read/Write Int (Heap) | 4.4M ops/s | 38K ops/s | 74K ops/s | 92K ops/s |
| Large Buffer (64KB) | 156K ops/s | 386 ops/s | 1.6K ops/s | 2.3K ops/s |
| Mixed Operations | 5.1M ops/s | 26K ops/s | 130K ops/s | 115K ops/s |
| Slice | 54.1M ops/s | 2.4M ops/s | 7.6M ops/s | 17.6M ops/s |

### Detailed Results by Platform

#### JVM

| Benchmark | ops/sec | Error |
|-----------|---------|-------|
| allocateHeap | 7,602,037 | ±339K |
| allocateDirect | 1,271,719 | ±407K |
| bulkOperationsDirect | 56,342,344 | ±1.0M |
| bulkOperationsHeap | 46,142,390 | ±763K |
| readWriteIntDirect | 4,421,143 | ±94K |
| readWriteIntHeap | 4,402,796 | ±63K |
| largeBufferOperations | 155,721 | ±2.7K |
| mixedOperations | 5,069,592 | ±70K |
| sliceBuffer | 54,074,331 | ±979K |

#### JavaScript (Node.js)

| Benchmark | ops/sec | Error |
|-----------|---------|-------|
| allocateHeap | 5,251,373 | ±49K |
| allocateDirect | 5,259,732 | ±37K |
| bulkOperationsDirect | 10,343,060 | ±87K |
| bulkOperationsHeap | 10,465,098 | ±96K |
| readWriteIntDirect | 36,281 | ±641 |
| readWriteIntHeap | 38,390 | ±510 |
| largeBufferOperations | 386 | ±7 |
| mixedOperations | 26,144 | ±152 |
| sliceBuffer | 2,359,881 | ±51K |

#### WasmJS

| Benchmark | ops/sec | Error |
|-----------|---------|-------|
| allocateHeap | 21,255,491 | ±979K |
| allocateDirect | 21,719,620 | ±478K |
| bulkOperationsDirect | 13,627,390 | ±350K |
| bulkOperationsHeap | 12,608,759 | ±444K |
| readWriteIntDirect | 111,235 | ±2.3K |
| readWriteIntHeap | 73,799 | ±2.4K |
| largeBufferOperations | 1,587 | ±121 |
| mixedOperations | 129,720 | ±2.2K |
| sliceBuffer | 7,644,115 | ±517K |

#### macOS ARM64 (Native)

| Benchmark | ops/sec | Error |
|-----------|---------|-------|
| allocateHeap | 3,227,222 | ±100K |
| allocateDirect | 3,237,938 | ±118K |
| bulkOperationsDirect | 17,246,375 | ±414K |
| bulkOperationsHeap | 17,491,069 | ±675K |
| readWriteIntDirect | 84,984 | ±4.3K |
| readWriteIntHeap | 92,092 | ±6.6K |
| largeBufferOperations | 2,282 | ±23 |
| mixedOperations | 114,938 | ±1.4K |
| sliceBuffer | 17,643,627 | ±530K |

#### Android (Pixel 6, API 35)

| Benchmark | Median (ns) | Runs |
|-----------|-------------|------|
| allocateHeap | 810 | 9,096 |
| allocateDirect | 1,195 | 6,143 |
| readWriteIntHeap | 7,587 | 1,008 |
| readWriteIntDirect | 7,657 | 1,002 |
| bulkOperationsHeap | 8,056 | 954 |
| bulkOperationsDirect | 5,827 | 1,330 |
| largeBufferOperations | 243,855 | 36 |
| mixedOperations | 64,152 | 119 |
| sliceBuffer | 5,746 | 1,329 |

## Running Benchmarks

### kotlinx-benchmark (JVM, JS, WasmJS, Native)

```bash
# All platforms
./gradlew benchmark

# Platform-specific
./gradlew jvmBenchmarkBenchmark
./gradlew jsBenchmarkBenchmark
./gradlew wasmJsBenchmarkBenchmark
./gradlew macosArm64BenchmarkBenchmark

# Quick validation (single iteration)
./gradlew quickBenchmark

# Subset (allocation only)
./gradlew subsetBenchmark

# Native-safe (excludes high-allocation benchmarks)
./gradlew nativeSafeBenchmark
```

### AndroidX Benchmark

```bash
# Requires connected device or emulator
./gradlew connectedBenchmarkAndroidTest

# Results output to:
# /sdcard/Android/media/com.ditchoom.buffer.test/additional_test_output/
```

## Platform-Specific Recommendations

### JVM/Android
- **Use Direct buffers** for I/O operations - they avoid an extra copy
- **Heap buffers** are faster to allocate (7.6M vs 1.3M ops/s)
- Bulk operations are extremely fast (46-56M ops/s)

### JavaScript
- Heap and Direct allocation are equivalent (both use Uint8Array)
- **Primitive read/write is slow** (36-38K ops/s) - batch operations when possible
- Bulk operations are reasonably fast (10M ops/s)

### WasmJS
- **Fastest allocation** of any platform (21M+ ops/s)
- Primitive operations are ~3x faster than JS (111K vs 36K ops/s)
- Good choice for compute-heavy workloads

### Apple/Native (iOS, macOS, etc.)
- Zero-copy slicing via pointer arithmetic (17.6M ops/s)
- Direct memory operations via memcpy for bulk writes
- Pointer reinterpretation for fast primitive reads

## Optimizations Applied

### Apple/Native Platform
1. **Zero-copy slice**: `MutableDataBufferSlice` uses pointer arithmetic instead of `subdataWithRange()`
2. **memcpy bulk writes**: Direct memory copy instead of intermediate ByteArray
3. **Pointer reinterpretation**: `readShort/Int/Long()` use `CPointer.reinterpret<>()`
4. **Direct pointer reads**: `readByteArray()` avoids NSData allocation

These optimizations improved Native performance by 1.3-1.7x and fixed OOM crashes during high-frequency slice operations.

### String Decoding (Apple Platforms)

The string decoding implementation uses platform-specific source sets to handle NSStringEncoding type differences across Apple platforms:

- **macOS, iOS, tvOS**: 64-bit platforms using `ULong` for NSStringEncoding
- **watchOS**: Mixed types (device uses `UInt`, simulators use `ULong`)

**Performance Impact**: Benchmark comparison shows **no regression** from string decoding changes:

| Platform | Benchmark | Before | After | Change |
|----------|-----------|--------|-------|--------|
| JVM | heapOperations | 149M ops/s | 162M ops/s | +9% |
| JS | heapOperations | 67M ops/s | 80M ops/s | +19% |
| WASM | linearBufferOps | 90M ops/s | 90M ops/s | ~0% |
| macOS | readWriteIntDirect | 99K ops/s | 238K ops/s | +140% |
| macOS | heapOperations | 14M ops/s | 31M ops/s | +117% |

The macOS improvements are from pointer-based optimizations in native code paths, not from string decoding changes (which are a separate code path used only for charset conversions).

### VarHandle vs Standard ByteBuffer (Android)

**Important Finding**: VarHandle is significantly SLOWER than standard ByteBuffer operations on Android's ART runtime.

| Operation | Standard ByteBuffer | VarHandle | Slowdown |
|-----------|---------------------|-----------|----------|
| Int R/W (Direct) | **6,658 ns** | 560,833 ns | **84x slower** |
| Long R/W (Direct) | **3,407 ns** | 279,619 ns | **82x slower** |
| Int R/W (Heap) | **8,086 ns** | 563,305 ns | **70x slower** |

*Tested on Realme RMX3933, Android 14 (API 34)*

**Why VarHandle is slower on Android:**
1. ART runtime hasn't optimized VarHandle operations like HotSpot JVM has
2. Polymorphic signature overhead causes boxing/unboxing
3. Standard `ByteBuffer.getInt()/putInt()` methods are already intrinsified

**Recommendation**: Use standard ByteBuffer methods for get/put operations on Android. Only use MethodHandle for private field access (like `nativeAddress`) where it provides ~10x improvement over reflection.
