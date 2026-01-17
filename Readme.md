Buffer
==========

See the [project website][docs] for documentation and APIs.

Buffer is a Kotlin Multiplatform library for platform-agnostic byte buffer
management. It delegates to native implementations on each platform to avoid
memory copies:

- **JVM/Android**: `java.nio.ByteBuffer` (Direct buffers for zero-copy I/O)
- **iOS/macOS**: `NSMutableData` (native) or `ByteArray` (managed)
- **JavaScript**: `Uint8Array` with `SharedArrayBuffer` support
- **WASM**: Native linear memory (Direct) or `ByteArray` (Heap)
- **Linux/Native**: `ByteArray`

## Features

- **Zero-copy performance**: Direct delegation to platform-native buffers
- **Buffer pooling**: High-performance buffer reuse for network I/O
- **Stream processing**: Handle fragmented data across chunk boundaries
- **Android IPC**: `SharedMemory` support for zero-copy inter-process communication

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/com.ditchoom/buffer.svg)](https://central.sonatype.com/artifact/com.ditchoom/buffer)

```kotlin
dependencies {
    // Core buffer library
    implementation("com.ditchoom:buffer:<latest-version>")

    // Optional: Compression support (gzip, deflate)
    implementation("com.ditchoom:buffer-compression:<latest-version>")
}
```

Find the latest version on [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/buffer).

## Modules

| Module | Description |
|--------|-------------|
| `buffer` | Core buffer interfaces and implementations |
| `buffer-compression` | Compression/decompression (gzip, deflate) |

## Quick Example

```kotlin
val buffer = PlatformBuffer.allocate(1024)
buffer.writeInt(42)
buffer.writeString("Hello!")
buffer.resetForRead()

val number = buffer.readInt()
val text = buffer.readString(6)
```

## Scoped Buffers (High Performance)

For performance-critical code paths, use `ScopedBuffer` with deterministic memory management:

```kotlin
withScope { scope ->
    val buffer = scope.allocate(8192)
    buffer.writeInt(42)
    buffer.writeString("Hello")
    buffer.resetForRead()

    val value = buffer.readInt()
    val text = buffer.readString(5)

    // Native address available for FFI/JNI
    val address = buffer.nativeAddress
} // Memory freed immediately when scope closes
```

**Benefits over `PlatformBuffer`:**
- **Deterministic cleanup**: Memory freed immediately, no GC pressure
- **Direct native access**: `nativeAddress` for FFI/JNI interop
- **Platform-optimized**: FFM on JVM 21+, Unsafe on older JVMs, malloc on native

## Compression Example

```kotlin
import com.ditchoom.buffer.compression.*

val data = "Hello, World!".toReadBuffer()
val compressed = compress(data, CompressionAlgorithm.Gzip).getOrThrow()
val decompressed = decompress(compressed, CompressionAlgorithm.Gzip).getOrThrow()
```

## Stream Processing

Handle fragmented data with `StreamProcessor`:

```kotlin
val processor = StreamProcessor.builder(pool).build()

// Append chunks as they arrive
processor.append(chunk1)
processor.append(chunk2)

// Parse protocol headers
val messageLength = processor.peekInt()
if (processor.available() >= 4 + messageLength) {
    processor.skip(4)
    val payload = processor.readBuffer(messageLength)
}
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
