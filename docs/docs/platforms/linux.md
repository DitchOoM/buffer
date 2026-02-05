---
sidebar_position: 6
title: Linux
---

# Linux Platform

Buffer on Kotlin/Native Linux uses native memory allocation (malloc/free) for zero-copy I/O operations, particularly optimized for io_uring.

## Implementation

| Zone | Linux Type | Use Case |
|------|------------|----------|
| `Heap` | `ByteArrayBuffer` | Managed memory, GC-friendly |
| `Direct` | `NativeBuffer` | Zero-copy I/O, io_uring, FFI |
| `SharedMemory` | `NativeBuffer` | Same as Direct |

## NativeBuffer: Native Memory

`NativeBuffer` uses `malloc`/`free` to allocate memory outside the Kotlin/Native GC heap:

- **Zero-copy I/O**: Memory can be passed directly to io_uring, epoll, or other system calls
- **No GC pressure**: Memory is not tracked by Kotlin/Native garbage collector
- **No pinning required**: Unlike `ByteArray`, no need to pin memory for native calls
- **Explicit cleanup**: Must be closed to free memory (or use `ScopedBuffer`)

### Memory Access

`NativeBuffer` implements `NativeMemoryAccess`, providing:

```kotlin
val buffer = NativeBuffer.allocate(65536)

// Direct native address for FFI/system calls
val ptr = buffer.nativeAddress.toCPointer<ByteVar>()!!
val size = buffer.nativeSize
```

## Usage with io_uring

`NativeBuffer` is designed for high-performance async I/O with io_uring:

```kotlin
val buffer = NativeBuffer.allocate(65536)
val ptr = buffer.nativeAddress.toCPointer<ByteVar>()!!

// Prepare io_uring read
io_uring_prep_recv(sqe, sockfd, ptr, buffer.capacity, 0)

// After completion, set position to bytes read
buffer.position(bytesRead)
buffer.resetForRead()

// Process data
val header = buffer.readInt()
// ...

// Caller must close when done
buffer.close()
```

## ScopedBuffer for Deterministic Cleanup

For automatic memory management, use `ScopedBuffer`:

```kotlin
withScope { scope ->
    val buffer = scope.allocate(65536)
    val ptr = buffer.nativeAddress.toCPointer<ByteVar>()!!

    // Use with io_uring...
    io_uring_prep_recv(sqe, sockfd, ptr, buffer.capacity.toULong(), 0)

    // Process after completion
    buffer.position(bytesRead)
    buffer.resetForRead()
    processData(buffer)
} // Memory freed automatically
```

## ByteArrayBuffer: Managed Memory

For workloads that don't need native memory access, `ByteArrayBuffer` provides GC-managed buffers:

```kotlin
// Explicitly request heap allocation
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)

// Or wrap existing ByteArray
val wrapped = PlatformBuffer.wrap(existingByteArray)
```

### When to Use Each

| Use Case | Recommended |
|----------|-------------|
| io_uring / epoll | `NativeBuffer` (Direct) |
| FFI / C interop | `NativeBuffer` (Direct) |
| General compute | `ByteArrayBuffer` (Heap) |
| Wrapping existing data | `ByteArrayBuffer` (wrap) |

## Native Data Conversion

Convert buffers to Linux-native `NativeBuffer` wrapper for FFI:

```kotlin
val buffer = NativeBuffer.allocate(1024)
buffer.writeBytes(data)
buffer.resetForRead()

// Get NativeBuffer wrapper (copies for safety)
val nativeData = buffer.toNativeData()
val nativeBuffer: NativeBuffer = nativeData.nativeBuffer

// Access native pointer
val ptr = nativeBuffer.nativeAddress.toCPointer<ByteVar>()
val size = nativeBuffer.nativeSize
```

### Zero-Copy Behavior

| Conversion | ByteArrayBuffer (Heap) | NativeBuffer (Direct) |
|------------|------------------------|----------------------|
| `toNativeData()` | Copy | Copy (new allocation) |
| `toMutableNativeData()` | Copy | Copy (new allocation) |
| `toByteArray()` | Zero-copy (backing array) | Copy |

:::note Why Copy?
`NativeBuffer` uses `malloc`-allocated memory that must be explicitly freed. Returning a view would create ownership ambiguity. For zero-copy access, use the `NativeBuffer` directly:

```kotlin
val buffer = NativeBuffer.allocate(1024)
val ptr = buffer.nativeAddress.toCPointer<ByteVar>()
// Use ptr directly with io_uring or other system calls
```
:::

### Direct Native Access

For zero-copy I/O, access the native pointer directly instead of using conversion:

```kotlin
val buffer = NativeBuffer.allocate(65536)

// Direct pointer access - zero copy
val ptr = buffer.nativeAddress.toCPointer<ByteVar>()!!

// Use with io_uring
io_uring_prep_recv(sqe, sockfd, ptr, buffer.capacity.toULong(), 0)

// After completion
buffer.position(bytesRead)
buffer.resetForRead()
val data = buffer.readByteArray(bytesRead)
```

See [Platform Interop](../recipes/platform-interop) for more details.

## SIMD-Accelerated Unicode (simdutf)

On Linux, `StreamingStringDecoder` uses [simdutf](https://github.com/simdutf/simdutf) for SIMD-accelerated UTF-8 to UTF-16 transcoding. This provides significant performance improvements for text-heavy workloads.

### Features

- **SIMD acceleration**: Uses SSE/AVX on x64, NEON on ARM64
- **Boundary detection**: Efficiently finds safe UTF-8 boundaries for chunk processing
- **Zero-copy decoding**: Converts directly from native buffer to CharArray

### Usage

```kotlin
import com.ditchoom.buffer.StreamingStringDecoder

val decoder = StreamingStringDecoder()
val result = StringBuilder()

// simdutf handles multi-byte sequences split across chunks
chunks.forEach { chunk ->
    decoder.decode(chunk, result)
}
decoder.finish(result)

println(result.toString())
```

:::note Platform Availability
simdutf is only used on Linux (linuxX64, linuxArm64). Other platforms use their native APIs:
- **JVM/Android**: `java.nio.charset.CharsetDecoder`
- **Apple**: Core Foundation string APIs
- **JS/WASM**: TextDecoder API
:::

## Best Practices

1. **Use Direct for I/O** - `NativeBuffer` avoids copies to/from kernel space
2. **Use ScopedBuffer** - Ensures memory is freed even on exceptions
3. **Pool buffers** - Reuse `NativeBuffer` instances to reduce malloc/free overhead
4. **Close explicitly** - If not using scopes, always close `NativeBuffer` when done
5. **Use Heap for short-lived data** - `ByteArrayBuffer` is simpler when native access isn't needed
6. **Use StreamingStringDecoder for text** - Leverages simdutf for fast UTF-8 decoding
