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
├── jvmCommonMain/       # Shared JVM/Android: BaseJvmBuffer, CharsetEncoderHelper
├── jvmMain/             # JVM: HeapJvmBuffer, DirectJvmBuffer, JvmBuffer
├── androidMain/         # Android: extends JVM + SharedMemory/Parcelable IPC
├── appleMain/           # iOS/macOS/watchOS/tvOS: MutableDataBuffer (NSMutableData)
├── jsMain/              # Browser/Node.js: JsBuffer (Int8Array, SharedArrayBuffer support)
├── wasmJsMain/          # WASM: LinearBuffer (native memory) + ByteArrayBuffer (heap)
├── nonJvmMain/          # Shared native/WASM: ByteArrayBuffer
└── nativeMain/          # Linux/Apple native: uses nonJvmMain
```

### Buffer Types by Platform

| Platform | Heap (wrap/Heap zone) | Direct (allocate) | Shared Memory |
|----------|----------------------|-------------------|---------------|
| JVM | `HeapJvmBuffer` | `DirectJvmBuffer` | Falls back to Direct |
| Android | `HeapJvmBuffer` | `DirectJvmBuffer` | `ParcelableSharedMemoryBuffer` |
| Apple | `ByteArrayBuffer` | `MutableDataBuffer` | Falls back to Direct |
| JS | `JsBuffer` | `JsBuffer` | `JsBuffer` (SharedArrayBuffer) |
| WASM | `ByteArrayBuffer` | `LinearBuffer` | Falls back to Direct |
| Linux | `ByteArrayBuffer` | `NativeBuffer` | Falls back to Direct |

### Memory Access Interfaces

- `NativeMemoryAccess` - Direct native memory pointer (DirectJvmBuffer, MutableDataBuffer, LinearBuffer, JsBuffer, NativeBuffer)
- `ManagedMemoryAccess` - Kotlin ByteArray backing (HeapJvmBuffer, ByteArrayBuffer)
- `SharedMemoryAccess` - Cross-process shared memory (ParcelableSharedMemoryBuffer, JsBuffer with SharedArrayBuffer)

### Key Interfaces

- `PlatformBuffer` - Main buffer interface combining read/write operations
- `ReadBuffer` - Read operations (relative and absolute)
- `WriteBuffer` - Write operations (relative and absolute)
- `AllocationZone` - Memory allocation strategy: `Heap`, `Direct`, `SharedMemory`, `Custom`

### Scoped Buffers (`com.ditchoom.buffer`)

High-performance buffers with deterministic memory management for performance-critical code:

- `BufferScope` - Manages lifetime of scoped buffers; all buffers freed when scope closes
- `ScopedBuffer` - Buffer with guaranteed native memory access and explicit cleanup
- `withScope { }` - Recommended entry point; creates scope and ensures cleanup

```kotlin
withScope { scope ->
    val buffer = scope.allocate(8192)
    buffer.writeInt(42)
    buffer.resetForRead()
    val value = buffer.readInt()

    // Native address for FFI/JNI
    val address = buffer.nativeAddress
} // All buffers freed here
```

**Platform Implementations:**

| Platform | Implementation | Allocation |
|----------|---------------|------------|
| JVM 21+  | `FfmBufferScope` | FFM Arena + MemorySegment |
| JVM < 21 | `UnsafeBufferScope` | Unsafe.allocateMemory |
| Android  | `UnsafeBufferScope` | Unsafe.allocateMemory |
| Native   | `NativeBufferScope` | malloc/free |
| WASM     | `WasmBufferScope` | LinearMemory |
| JS       | `JsBufferScope` | GC-managed ArrayBuffer |

**When to use ScopedBuffer vs PlatformBuffer:**
- Use `ScopedBuffer` for: FFI/JNI interop, zero-copy I/O, avoiding GC pressure
- Use `PlatformBuffer` for: General-purpose buffering, long-lived buffers

### Buffer Comparison & Search Methods

ReadBuffer provides optimized search and comparison operations:

```kotlin
// Content comparison (optimized with SIMD on JVM 11+)
buffer1.contentEquals(buffer2)  // true if remaining bytes are identical
buffer1.mismatch(buffer2)       // index of first difference, or -1

// Search for values (uses bulk Long comparisons, XOR zero-detection)
buffer.indexOf(0x42.toByte())           // find byte
buffer.indexOf(0x1234.toShort())        // find Short (respects byte order)
buffer.indexOf(0x12345678)              // find Int
buffer.indexOf(0x123456789ABCDEF0L)     // find Long
buffer.indexOf("Hello")                 // find string (UTF-8 default)
buffer.indexOf(otherBuffer)             // find byte sequence
```

### Buffer Fill Methods

WriteBuffer provides optimized fill operations (writes 8 bytes at a time internally):

```kotlin
// Fill remaining space with value
buffer.fill(0x00.toByte())      // zero-fill (uses Long writes internally)
buffer.fill(0x1234.toShort())   // fill with Short pattern
buffer.fill(0x12345678)         // fill with Int pattern
buffer.fill(0x123456789ABCDEF0L) // fill with Long pattern
```

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

- **JVM/Android:** Direct ByteBuffers (`DirectJvmBuffer`) used by default; `HeapJvmBuffer` for `wrap()` and `Heap` zone
- **Android SharedMemory:** Use `AllocationZone.SharedMemory` for zero-copy IPC via Parcelable (API 27+)
- **Apple:** `MutableDataBuffer` wraps NSMutableData (native memory); `wrap(ByteArray)` returns `ByteArrayBuffer`
- **Apple NSData interop:** Use `PlatformBuffer.wrap(nsData)` or `PlatformBuffer.wrap(nsMutableData)` for zero-copy Apple API interop
- **JS SharedArrayBuffer:** Requires CORS headers (`Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy`)
- **WASM:** `LinearBuffer` (Direct) uses native WASM memory for JS interop; `ByteArrayBuffer` (Heap) for compute workloads
- **Linux:** `NativeBuffer` (Direct) uses malloc/free for zero-copy io_uring I/O; `ByteArrayBuffer` (Heap) for managed memory

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
1. **LinearBuffer (Direct)** - Uses native WASM Pointer ops, 25% faster than ByteArrayBuffer
2. **ByteArrayBuffer (Heap)** - Use for high-frequency allocations (no memory limit)
3. **Bulk operations are 2x faster** than single operations on LinearBuffer
4. **256MB pre-allocated** - LinearBuffer uses bump allocator due to optimizer bug workaround
5. **Use Direct for JS interop** - Zero-copy sharing with JavaScript via `wasmMemory.buffer`

See `PERFORMANCE.md` for detailed benchmark results.

## CI/CD

- Builds run on macOS with JDK 21
- PR labels control version bumping: `major`, `minor`, or patch (default)
- Publishing to Maven Central happens automatically on PR merge to main
