---
slug: /
sidebar_position: 1
title: Introduction
---

# Buffer

**Kotlin Multiplatform byte buffers — same code on JVM, Android, iOS, macOS, Linux, JS, and WASM with zero-copy performance.**

Buffer gives you one `ReadBuffer`/`WriteBuffer` API that delegates to platform-native types — `ByteBuffer` on JVM, `NSData` on Apple, `malloc` on Linux, `Uint8Array` on JS — so data never copies between your code and the OS.

## Why Buffer?

- **Zero-copy I/O**: Delegates directly to platform-native buffers — no copying between your code and the OS
- **Buffer Pooling**: Allocate once, reuse for every network request — no GC pauses, predictable latency
- **Stream Processing**: Parse protocols that span chunk boundaries without manual accumulator code
- **Compression**: `compress()`/`decompress()` on any `ReadBuffer` across all platforms
- **Flow Extensions**: `mapBuffer()`, `asStringFlow()`, `lines()` — compose streaming transforms with Kotlin Flow
- **SIMD-Accelerated Operations**: Bulk comparison, search (`indexOf`), fill, and XOR masking
- **7 Platforms**: Same API on JVM, Android, iOS, macOS, Linux, JS, WASM

## Quick Example

```kotlin
// Works identically on all platforms
val buffer = PlatformBuffer.allocate(1024)
buffer.writeInt(42)
buffer.writeString("Hello, Buffer!")
buffer.resetForRead()

val number = buffer.readInt()      // 42
val text = buffer.readString(14)   // "Hello, Buffer!"
```

### High-Value Patterns

**Buffer pooling** — no allocation per request:

```kotlin
withPool(defaultBufferSize = 8192) { pool ->
    pool.withBuffer(1024) { buffer ->
        buffer.writeInt(requestId)
        buffer.writeString(payload)
        buffer.resetForRead()
        sendToNetwork(buffer)
    } // buffer returned to pool
}
```

**Streaming pipeline** — decompress, decode, split lines:

```kotlin
bufferFlow
    .mapBuffer { decompress(it, Gzip).getOrThrow() }
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

## Platform Implementations

| Platform | Native Type | Notes |
|----------|-------------|-------|
| JVM | [`java.nio.ByteBuffer`](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html) | Direct buffers for zero-copy I/O |
| Android | [`ByteBuffer`](https://developer.android.com/reference/java/nio/ByteBuffer) + [`SharedMemory`](https://developer.android.com/reference/android/os/SharedMemory) | IPC via Parcelable |
| iOS/macOS | [`NSData`](https://developer.apple.com/documentation/foundation/nsdata) / [`NSMutableData`](https://developer.apple.com/documentation/foundation/nsmutabledata) | Foundation integration |
| JavaScript | [`Uint8Array`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Uint8Array) | SharedArrayBuffer support |
| WASM | `LinearBuffer` / `ByteArrayBuffer` | Zero-copy JS interop |
| Linux | `NativeBuffer` (malloc) / `ByteArrayBuffer` | Zero-copy io_uring I/O |

## Part of the DitchOoM Stack

Buffer is the foundation for the [Socket](https://github.com/DitchOoM/socket) library — cross-platform TCP + TLS that streams these same `ReadBuffer`/`WriteBuffer` types.

```
┌─────────────────────────────┐
│  Your Protocol              │
├─────────────────────────────┤
│  socket (TCP + TLS)         │  ← com.ditchoom:socket
├─────────────────────────────┤
│  buffer-compression         │  ← com.ditchoom:buffer-compression
├─────────────────────────────┤
│  buffer-flow                │  ← com.ditchoom:buffer-flow
├─────────────────────────────┤
│  buffer                     │  ← com.ditchoom:buffer
└─────────────────────────────┘
```

## Installation

Add to your `build.gradle.kts` (see [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/buffer) for the latest version):

```kotlin
dependencies {
    // Core buffer library
    implementation("com.ditchoom:buffer:<latest-version>")

    // Optional: Compression (gzip, deflate)
    implementation("com.ditchoom:buffer-compression:<latest-version>")

    // Optional: Flow extensions (lines, mapBuffer, asStringFlow)
    implementation("com.ditchoom:buffer-flow:<latest-version>")
}
```

## Next Steps

- [Getting Started](./getting-started) - Installation and first buffer
- [Core Concepts](./core-concepts/buffer-basics) - Understanding buffers, positions, and limits
- [Recipes](./recipes/basic-operations) - Common patterns and examples
- [Performance](./performance) - Optimization tips
