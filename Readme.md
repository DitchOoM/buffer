Buffer
==========

See the [project website][docs] for documentation and APIs.

**Kotlin Multiplatform byte buffers — same code on JVM, Android, iOS, macOS, Linux, JS, and WASM with zero-copy performance.**

Buffer gives you one `ReadBuffer`/`WriteBuffer` API that delegates to platform-native types — `ByteBuffer` on JVM, `NSData` on Apple, `malloc` on Linux, `Uint8Array` on JS — so data never copies between your code and the OS.

[![Maven Central](https://img.shields.io/maven-central/v/com.ditchoom/buffer.svg)](https://central.sonatype.com/artifact/com.ditchoom/buffer)

## Why Buffer?

**Why not just use `ByteArray`?**

| Concern | ByteArray | Buffer |
|---------|-----------|--------|
| Platform I/O | Copy into `ByteBuffer`/`NSData`/`Uint8Array` every time | Zero-copy — delegates to the native type directly |
| Memory reuse | Allocate per request, GC cleans up | `BufferPool` — acquire, use, release, same buffer reused |
| Fragmented data | Manual accumulator + boundary tracking | `StreamProcessor` — peek/read across chunk boundaries |
| Compression | Platform-specific zlib wrappers | `compress()`/`decompress()` on any `ReadBuffer` |
| Streaming transforms | Build your own pipeline | `mapBuffer()`, `asStringFlow()`, `lines()` — compose with Flow |
| Cross-platform | Only type that's truly portable | Same API on JVM, Android, iOS, macOS, Linux, JS, WASM |

## Installation

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

Find the latest version on [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/buffer).

## Modules

| Module | Description |
|--------|-------------|
| `buffer` | Core `ReadBuffer`/`WriteBuffer` interfaces, `BufferPool`, `StreamProcessor` |
| `buffer-compression` | `compress()`/`decompress()` (gzip, deflate) on `ReadBuffer` |
| `buffer-flow` | Kotlin Flow extensions: `mapBuffer()`, `asStringFlow()`, `lines()` |

## Quick Example

```kotlin
// Works identically on JVM, Android, iOS, macOS, Linux, JS, WASM
val buffer = PlatformBuffer.allocate(1024)
buffer.writeInt(42)
buffer.writeString("Hello!")
buffer.resetForRead()

val number = buffer.readInt()     // 42
val text = buffer.readString(6)   // "Hello!"
```

## Buffer Pooling

Allocate once, reuse for every request — no GC pressure:

```kotlin
withPool(defaultBufferSize = 8192) { pool ->
    repeat(10_000) {
        pool.withBuffer(1024) { buffer ->
            buffer.writeInt(requestId)
            buffer.writeString(payload)
            buffer.resetForRead()
            sendToNetwork(buffer)
        } // buffer returned to pool, not GC'd
    }
} // pool cleared
```

Without pooling: 10,000 requests = 10,000 allocations. With pooling: 10,000 requests = ~64 allocations (`maxPoolSize`).

## Stream Processing

Parse protocols that span chunk boundaries — no manual accumulator code:

```kotlin
val processor = StreamProcessor.create(pool)

// Chunks arrive from the network
processor.append(chunk1)  // partial message
processor.append(chunk2)  // rest of message + start of next

// Parse length-prefixed messages across boundaries
while (processor.available() >= 4) {
    val length = processor.peekInt()
    if (processor.available() < 4 + length) break  // wait for more data
    processor.skip(4)
    val payload = processor.readBuffer(length)
    handleMessage(payload)
}
```

## Compression

Compress and decompress any `ReadBuffer` — works on all platforms:

```kotlin
val data = "Hello, World!".toReadBuffer()
val compressed = compress(data, CompressionAlgorithm.Gzip).getOrThrow()
val decompressed = decompress(compressed, CompressionAlgorithm.Gzip).getOrThrow()
```

## Flow Extensions

Compose streaming transforms with Kotlin Flow:

```kotlin
// Transform a flow of raw buffers into processed lines
bufferFlow
    .mapBuffer { decompress(it, Gzip).getOrThrow() }
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

## Scoped Buffers

For FFI/JNI interop or deterministic memory management:

```kotlin
withScope { scope ->
    val buffer = scope.allocate(8192)
    buffer.writeInt(42)
    buffer.writeString("Hello")
    buffer.resetForRead()

    // Native address available for FFI/JNI
    val address = buffer.nativeAddress
} // Memory freed immediately when scope closes
```

## Platform Implementations

| Platform | Native Type | Notes |
|----------|-------------|-------|
| JVM | `java.nio.ByteBuffer` | Direct buffers for zero-copy NIO |
| Android | `ByteBuffer` + `SharedMemory` | IPC via Parcelable |
| iOS/macOS | `NSMutableData` | Foundation integration |
| JavaScript | `Uint8Array` | `SharedArrayBuffer` support |
| WASM | `LinearBuffer` / `ByteArrayBuffer` | Zero-copy JS interop |
| Linux | `NativeBuffer` (malloc/free) | Zero-copy io_uring I/O |

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

## License

    Copyright 2022 DitchOoM

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

[docs]: https://ditchoom.github.io/buffer/
