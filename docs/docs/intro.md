---
slug: /
sidebar_position: 1
title: Introduction
---

# Buffer

**Kotlin Multiplatform library for platform-agnostic byte buffer management**

Buffer provides a unified API for managing byte buffers across all Kotlin platforms, delegating to native implementations to avoid memory copies.

## Why Buffer?

- **Zero-copy performance**: Direct delegation to platform-native buffers (ByteBuffer, NSData, Uint8Array)
- **Kotlin Multiplatform**: Single API works across JVM, Android, iOS, JS, WASM, and Native
- **Buffer Pooling**: High-performance buffer reuse for network I/O and protocol parsing
- **Stream Processing**: Handle fragmented data across chunk boundaries
- **Optimized Operations**: Bulk comparison, search (indexOf), and fill operations using SIMD-like techniques

## Platform Implementations

| Platform | Native Type | Notes |
|----------|-------------|-------|
| JVM | [`java.nio.ByteBuffer`](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html) | Use Direct buffers for zero-copy I/O |
| Android | [`ByteBuffer`](https://developer.android.com/reference/java/nio/ByteBuffer) + [`SharedMemory`](https://developer.android.com/reference/android/os/SharedMemory) | IPC via Parcelable |
| iOS/macOS | [`NSData`](https://developer.apple.com/documentation/foundation/nsdata) / [`NSMutableData`](https://developer.apple.com/documentation/foundation/nsmutabledata) | Foundation integration |
| JavaScript | [`Uint8Array`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Uint8Array) | SharedArrayBuffer support |
| WASM | `LinearBuffer` (native memory) / `ByteArrayBuffer` | Zero-copy JS interop |
| Linux | [`ByteArray`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/) | Native target |

## Quick Example

```kotlin
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder

// Allocate a buffer
val buffer = PlatformBuffer.allocate(
    size = 1024,
    zone = AllocationZone.Direct,
    byteOrder = ByteOrder.BIG_ENDIAN
)

// Write data
buffer.writeInt(42)
buffer.writeString("Hello, Buffer!")

// Prepare for reading
buffer.resetForRead()

// Read data
val number = buffer.readInt()      // 42
val text = buffer.readString(14)   // "Hello, Buffer!"
```

## Installation

Add to your `build.gradle.kts` (see [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/buffer) for the latest version):

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
}
```

## Next Steps

- [Getting Started](./getting-started) - Installation and basic usage
- [Core Concepts](./core-concepts/buffer-basics) - Understanding buffers, positions, and limits
- [Recipes](./recipes/basic-operations) - Common patterns and examples
- [Performance](./performance) - Optimization tips
