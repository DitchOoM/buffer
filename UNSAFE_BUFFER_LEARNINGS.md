# UnsafeBuffer Implementation Learnings

This document captures the research, benchmarks, and design decisions made while implementing the UnsafeBuffer API for cross-platform native memory operations.

## Overview

The goal was to create a unified `UnsafeMemory` API that provides fast, direct memory access across all Kotlin Multiplatform targets while maintaining a clean, easy-to-use API.

## Architecture

### Core Components

1. **`UnsafeBuffer`** (interface) - Main buffer interface for unsafe memory operations
2. **`DefaultUnsafeBuffer`** (class) - Standard implementation using `UnsafeMemory`
3. **`UnsafeMemory`** (expect/actual object) - Platform-specific memory operations
4. **`withUnsafeBuffer()`** - Scoped allocation with automatic cleanup

### Platform Implementations

| Platform | Memory Backend | Bulk Operations |
|----------|---------------|-----------------|
| **JVM** | `sun.misc.Unsafe` | `unsafe.copyMemory()` |
| **Android** | `sun.misc.Unsafe` | `unsafe.copyMemory()` |
| **Native (macOS/iOS/Linux)** | `malloc`/`free` + typed pointers | POSIX `memcpy`/`memset` |
| **JS** | `ArrayBuffer` + `DataView`/`Int8Array` | `Int8Array.set()` |
| **WASM** | `ByteArray` with `copyInto()` | Kotlin stdlib bulk ops |

## Performance Benchmarks

### UnsafeBuffer vs PlatformBuffer

Tested with 10,000 iterations, 1024-byte buffers:

| Operation | JVM | Native (macOS) | JS | WASM |
|-----------|-----|----------------|-----|------|
| Int Write | 0.80x | **2.82x** | **1.89x** | **1.74x** |
| Int Read | 1.09x | **2.62x** | **1.92x** | **2.30x** |
| Long Write | 0.84x | **5.82x** | **5.09x** | **3.27x** |
| Mixed Ops | **1.49x** | **2.85x** | **2.68x** | **2.57x** |
| ByteArray Write | 0.42x | **7.53x** | 0.15x* | 0.16x* |
| ByteArray Read | 0.38x | **21.49x** | 0.13x* | 0.26x* |

*Before JS optimization. After optimization with `Int8Array.set()`, JS byte array performance improved significantly.

### Key Findings

1. **Native is optimal**: Direct pointer access with `memcpy` provides 2.8x-21.5x speedup
2. **JVM is competitive**: `ByteBuffer` has JVM intrinsics that are hard to beat
3. **JS/WASM primitive ops are fast**: Direct typed access provides 1.7x-5.1x speedup
4. **JS/WASM bulk ops were slow**: Original byte-by-byte loops were 5-8x slower than PlatformBuffer

## Platform-Specific Optimizations

### Native (macOS/iOS/Linux)

Uses POSIX `memcpy` and `memset` for bulk operations:

```kotlin
actual fun copyToArray(address: Long, offset: Int, dest: ByteArray, destOffset: Int, length: Int) {
    val ptr = address.toCPointer<ByteVar>()!!.plus(offset)
    memcpy(dest.refTo(destOffset), ptr, length.toULong())
}
```

**Result**: 21.5x faster byte array reads compared to PlatformBuffer.

### JS

Added native `Int8Array.set()` for bulk operations instead of byte-by-byte loops:

```kotlin
actual fun copyFromArray(src: ByteArray, srcOffset: Int, address: Long, offset: Int, length: Int) {
    val destArray = getHolder(address).int8Array
    val srcInt8 = src.unsafeCast<Int8Array>().subarray(srcOffset, srcOffset + length)
    destArray.set(srcInt8, offset)  // Native browser API
}
```

Also added `BufferHolder` class to reduce map lookups from 3 to 1 per operation.

### WASM

#### memory.copy Research

WASM has `memory.copy` (opcode 0xFC_0A) and `memory.fill` (0xFC_0B) instructions defined in Kotlin/WASM's internal `WasmOp`:

```kotlin
// In JetBrains/kotlin stdlib (internal)
const val MEMORY_COPY = "MEMORY_COPY"
const val MEMORY_FILL = "MEMORY_FILL"
```

**However**, these are NOT exposed in the public `kotlin.wasm.unsafe` API. The `@WasmOp` annotation is internal, so user code cannot directly emit these instructions.

#### WASM Strategy Comparison

Tested two approaches:

| Approach | ByteArray Write | ByteArray Read | Int Write | Mixed Ops |
|----------|----------------|----------------|-----------|-----------|
| **Pointer-based** (WasmNativeUnsafeBuffer) | 18.7ms | 8.4ms | 13.8ms | 8.0ms |
| **ByteArray-based** (DefaultUnsafeBuffer) | 5.0ms | 6.9ms | 34.3ms | 13.4ms |

**Conclusions**:
- Pointer-based: 2.5x faster for primitives, 3.7x slower for byte arrays
- ByteArray-based: 3.7x faster for byte arrays, 2.5x slower for primitives

**Recommendation**: Use ByteArray-based approach for WASM since:
1. Byte array operations are more common in real-world use cases
2. The performance difference is more significant (3.7x vs 2.5x)
3. Simpler, more maintainable code

### Apple Platforms (iOS/macOS)

Added NSData wrapping capability for zero-copy interop:

```kotlin
// In appleMain
fun DefaultUnsafeBuffer.asNSData(length: Int = remaining()): NSData {
    val ptr = address.toCPointer<ByteVar>()
    return NSData.dataWithBytesNoCopy(
        bytes = ptr?.plus(position()),
        length = length.toULong(),
        freeWhenDone = false,  // Buffer manages its own memory
    )
}
```

**Note**: This creates a view, not a copy. The NSData is only valid while the UnsafeBuffer is alive.

## API Design Decisions

### Why UnsafeBuffer is not PlatformBuffer

`UnsafeBuffer` implements `ReadBuffer` and `WriteBuffer` but NOT `PlatformBuffer` because:
1. Native memory addresses are not valid across process boundaries (breaks Parcelable on Android)
2. Different lifecycle management (explicit close vs GC)
3. Different allocation semantics

### Scoped API Pattern

```kotlin
withUnsafeBuffer(size, byteOrder) { buffer ->
    buffer.writeInt(42)
    buffer.readInt()
}  // Automatically freed
```

This pattern:
- Ensures memory is always freed
- Works across all platforms including WASM
- Prevents memory leaks from forgotten `close()` calls

## Future Improvements

1. **Kotlin/WASM memory.copy**: File feature request for public `memory.copy`/`memory.fill` APIs
2. **Buffer pooling**: For high-throughput scenarios, implement buffer pool to reduce allocation overhead
3. **Streaming API**: For large data, consider streaming variant that reuses buffers
4. **Real-world benchmarks**: Add protocol parsing benchmarks (MQTT, HTTP) to validate performance gains

## Files Changed

- `src/commonMain/kotlin/com/ditchoom/buffer/UnsafeBuffer.kt` - Interface + DefaultUnsafeBuffer
- `src/commonMain/kotlin/com/ditchoom/buffer/ScopedUnsafeBuffer.kt` - expect declaration
- `src/*/kotlin/com/ditchoom/buffer/UnsafeMemory.kt` - Platform-specific implementations
- `src/*/kotlin/com/ditchoom/buffer/ScopedUnsafeBuffer.kt` - Platform-specific scoped APIs
- `src/appleMain/kotlin/com/ditchoom/buffer/UnsafeBufferExtensions.kt` - NSData wrapping
- `src/commonTest/kotlin/com/ditchoom/buffer/UnsafeBufferPerformanceTest.kt` - Benchmarks

## References

- [Kotlin/WASM unsafe API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.wasm.unsafe/)
- [WebAssembly bulk memory operations](https://github.com/WebAssembly/bulk-memory-operations)
- [Kotlin stdlib WASM source](https://github.com/JetBrains/kotlin/tree/master/libraries/stdlib/wasm/src/kotlin/wasm/unsafe)
