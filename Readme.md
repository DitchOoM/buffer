Buffer
==========

See the [project website][docs] for documentation and APIs.

Buffer is a Kotlin Multiplatform library for platform-agnostic byte buffer
management. It delegates to native implementations on each platform to avoid
memory copies:

- **JVM/Android**: `java.nio.ByteBuffer` (Direct buffers for zero-copy I/O)
- **iOS/macOS**: `NSData` / `NSMutableData`
- **JavaScript**: `Uint8Array` with `SharedArrayBuffer` support
- **WASM/Native**: `ByteArray`

## Features

- **Zero-copy performance**: Direct delegation to platform-native buffers
- **Buffer pooling**: High-performance buffer reuse for network I/O
- **Stream processing**: Handle fragmented data across chunk boundaries
- **Android IPC**: `SharedMemory` support for zero-copy inter-process communication

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/com.ditchoom/buffer.svg)](https://central.sonatype.com/artifact/com.ditchoom/buffer)

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
}
```

Find the latest version on [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/buffer).

## Quick Example

```kotlin
val buffer = PlatformBuffer.allocate(1024)
buffer.writeInt(42)
buffer.writeString("Hello!")
buffer.resetForRead()

val number = buffer.readInt()
val text = buffer.readString(6)
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
