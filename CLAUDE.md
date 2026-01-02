# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ByteBuffer is a Kotlin Multiplatform library providing platform-agnostic byte buffer management with an API similar to Java's ByteBuffer. It delegates to native implementations on each platform to avoid memory copies.

**Package:** `com.ditchoom.buffer`

## Build Commands

```bash
# Build for all platforms
./gradlew build

# Run tests
./gradlew allTests                # Run tests for all platforms (aggregated report)
./gradlew check                   # Run all checks (tests + linting)
./gradlew test                    # Common/JVM tests
./gradlew connectedCheck          # Android instrumented tests (requires emulator)

# Linting
./gradlew ktlintCheck             # Check code style
./gradlew ktlintFormat            # Auto-format code

# Run specific test class
./gradlew :jvmTest --tests "com.ditchoom.buffer.BufferTests"
./gradlew :jsNodeTest --tests "com.ditchoom.buffer.BufferTests"
```

## Architecture

### Kotlin Multiplatform Structure

The project uses the expect/actual pattern with platform-specific implementations:

```
src/
├── commonMain/          # Shared interfaces (PlatformBuffer, ReadBuffer, WriteBuffer)
├── commonTest/          # Shared tests run on all platforms
├── jvmMain/             # JVM: wraps java.nio.ByteBuffer
├── androidMain/         # Android: extends JVM + SharedMemory/Parcelable IPC
├── appleMain/           # iOS/macOS/watchOS/tvOS: wraps NSData/NSMutableData
├── jsMain/              # Browser/Node.js: wraps Uint8Array (SharedArrayBuffer support)
├── wasmJsMain/          # WASM: uses Kotlin ByteArray (optimization contributions welcome)
└── nativeMain/          # Linux: uses Kotlin ByteArray
```

### Key Interfaces

- `PlatformBuffer` - Main buffer interface combining read/write operations
- `ReadBuffer` - Read operations (relative and absolute)
- `WriteBuffer` - Write operations (relative and absolute)
- `AllocationZone` - Memory allocation strategy: `Heap`, `Direct`, `SharedMemory`, `Custom`

### Buffer Pool (`com.ditchoom.buffer.pool`)

High-performance buffer pooling for minimizing allocations:

- `BufferPool` - Main pool interface with `SingleThreaded` and `MultiThreaded` modes
- `PooledBuffer` - Buffer acquired from pool, must call `release()` when done
- `withBuffer { }` - Recommended: auto-acquires and releases buffer
- `withPool { }` - Creates pool, runs block, clears pool on exit

```kotlin
// Preferred usage pattern
withPool(defaultBufferSize = 8192) { pool ->
    pool.withBuffer(1024) { buffer ->
        buffer.writeInt(42)
    }
}
```

### Buffer Stream (`com.ditchoom.buffer.stream`)

Chunked processing for large buffers and streaming data:

- `BufferStream` - Iterates over a buffer in fixed-size chunks
- `StreamProcessor` - Handles fragmented data (e.g., network packets) with peek/read operations

```kotlin
val processor = StreamProcessor.create(pool)
processor.append(networkData)
val length = processor.readInt()
val payload = processor.readBuffer(length)
```

### Factory Pattern

Buffers are created via companion object methods:
```kotlin
PlatformBuffer.allocate(size, zone = AllocationZone.Direct, byteOrder = ByteOrder.BIG_ENDIAN)
PlatformBuffer.wrap(byteArray)
```

## Platform Notes

- **JVM/Android:** Direct ByteBuffers used by default to avoid copies
- **Android SharedMemory:** Use `AllocationZone.SharedMemory` for zero-copy IPC via Parcelable
- **JS SharedArrayBuffer:** Requires CORS headers (`Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy`)
- **WASM:** Currently uses ByteArray copies - optimization contributions welcome

## Benchmarking

### Running Benchmarks

```bash
# All platforms (kotlinx-benchmark)
./gradlew benchmark

# Platform-specific
./gradlew jvmBenchmarkBenchmark
./gradlew jsBenchmarkBenchmark
./gradlew wasmJsBenchmarkBenchmark
./gradlew macosArm64BenchmarkBenchmark

# Quick validation (single iteration)
./gradlew quickBenchmark

# Android (requires device/emulator)
./gradlew connectedBenchmarkAndroidTest
```

### Benchmark Source Locations

- `src/commonBenchmark/kotlin/` - Shared benchmarks for JVM, JS, WasmJS, Native
- `src/androidInstrumentedTest/kotlin/` - AndroidX Benchmark tests

## Performance Optimization Guidelines

When optimizing buffer operations, follow these principles:

### Apple/Native Platform
1. **Avoid object allocation in hot paths** - Kotlin/Native GC can't keep up with millions of allocations/sec
2. **Use pointer arithmetic** - `CPointer + offset` compiles to single CPU instruction, zero allocation
3. **Use `reinterpret<>()` for multi-byte reads** - Avoids byte-by-byte assembly
4. **Prefer `memcpy` over intermediate arrays** - Direct memory-to-memory copy
5. **Avoid `subdataWithRange()`** - Creates new NSData objects, causes GC pressure

### JVM/Android
1. **Direct ByteBuffers** are best for I/O (avoid extra copy)
2. **Heap allocation is faster** (7.6M vs 1.3M ops/s) but Direct is better for I/O
3. **Bulk operations are extremely fast** (46-56M ops/s)

### JavaScript
1. **Batch operations** - Primitive read/write is slow (36K ops/s)
2. **Use bulk operations** when possible (10M ops/s)
3. Heap and Direct are equivalent (both use Uint8Array)

### WasmJS
1. **Fastest allocation** of any platform (21M+ ops/s)
2. Primitive operations are ~3x faster than JS
3. Good choice for compute-heavy workloads

See `PERFORMANCE.md` for detailed benchmark results.

## CI/CD

- Builds run on macOS with JDK 19
- PR labels control version bumping: `major`, `minor`, or patch (default)
- Publishing to Maven Central happens automatically on PR merge to main
