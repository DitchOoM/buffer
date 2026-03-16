---
sidebar_position: 2
title: Buffer Factories
---

# Buffer Factories

Buffer factories determine how buffer memory is allocated. Instead of passing a zone parameter, you choose (or create) a factory that encapsulates the allocation strategy.

## Overview

```kotlin
import com.ditchoom.buffer.BufferFactory

// Platform-optimal native memory (recommended)
val buffer = BufferFactory.Default.allocate(1024)

// GC-managed heap memory
val managed = BufferFactory.managed().allocate(1024)

// Cross-process shared memory (for IPC on Android)
val shared = BufferFactory.shared().allocate(1024)

// Convenience shorthand (delegates to BufferFactory.Default):
// val buffer = PlatformBuffer.allocate(1024)
```

## Built-in Presets

### BufferFactory.Default

Platform-optimal native memory. This is what `PlatformBuffer.allocate()` uses.

```kotlin
val buffer = BufferFactory.Default.allocate(1024)
```

**Characteristics:**
- Off-heap / native memory where available
- Zero-copy I/O with native code
- Best for network I/O and file operations
- Default allocation strategy

**Platform behavior:**
| Platform | Implementation |
|----------|---------------|
| JVM | [`DirectByteBuffer`](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html#allocateDirect-int-) |
| Android | [`DirectByteBuffer`](https://developer.android.com/reference/java/nio/ByteBuffer#allocateDirect(int)) |
| iOS/macOS | [`NSMutableData`](https://developer.apple.com/documentation/foundation/nsmutabledata) |
| JavaScript | [`Int8Array`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Int8Array) |
| WASM | `LinearBuffer` (native WASM memory) |
| Linux | `NativeBuffer` (malloc) |

![Heap vs Direct Memory](/img/heap-vs-direct.svg)

### BufferFactory.managed()

GC-managed heap memory backed by Kotlin `ByteArray`.

```kotlin
val buffer = BufferFactory.managed().allocate(1024)
```

**Characteristics:**
- Managed by garbage collector
- May require copying for native I/O operations
- Good for short-lived, small buffers

**Platform behavior:**
| Platform | Implementation |
|----------|---------------|
| JVM | [`HeapByteBuffer`](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html) |
| Android | [`HeapByteBuffer`](https://developer.android.com/reference/java/nio/ByteBuffer) |
| WASM | `ByteArrayBuffer` |
| Others | `ByteArrayBuffer` |

### BufferFactory.shared()

Memory that can be shared across process boundaries.

```kotlin
val buffer = BufferFactory.shared().allocate(1024)
```

**Characteristics:**
- Enables zero-copy IPC (Inter-Process Communication)
- Platform-specific availability
- Useful for Android services and multi-process apps

**Platform behavior:**
| Platform | Implementation |
|----------|---------------|
| Android (API 27+) | [`SharedMemory`](https://developer.android.com/reference/android/os/SharedMemory) |
| Android (< API 27) | Falls back to `DirectByteBuffer` |
| JavaScript | [`SharedArrayBuffer`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer) (requires CORS headers) |
| WASM | `LinearBuffer` (falls back to Default) |
| Others | Falls back to Default |

![SharedMemory IPC](/img/shared-memory-ipc.svg)

### BufferFactory.Deterministic

Buffers with guaranteed resource cleanup, independent of garbage collection.

```kotlin
BufferFactory.Deterministic.allocate(1024).use { buffer ->
    buffer.writeInt(42)
} // freed immediately, no GC needed
```

**Platform behavior:**
| Platform | Implementation |
|----------|---------------|
| JVM 9+ | `DeterministicDirectJvmBuffer` (DirectByteBuffer + Unsafe.invokeCleaner) |
| JVM 8 / Android | `UnsafePlatformBuffer` (Unsafe.allocateMemory/freeMemory) |
| JVM 21+ | `FfmBuffer` (FFM Arena-backed) |
| Apple | `MutableDataBuffer` (ARC-managed, already deterministic) |
| Linux | `NativeBuffer` (malloc/free, already deterministic) |
| WASM | `LinearBuffer` (linear memory, already deterministic) |
| JS | `JsBuffer` (GC-managed, no deterministic alternative) |

## Composable Decorators

Layer behaviors on top of any factory:

```kotlin
val factory = BufferFactory.Default
    .requiring<NativeMemoryAccess>()   // throw if buffer lacks native access
    .withSizeLimit(1_048_576)          // cap allocation size
    .withPooling(pool)                 // recycle buffers
```

## Custom Factories

Implement the interface directly or delegate:

```kotlin
class MonitoredFactory(
    private val delegate: BufferFactory = BufferFactory.Default,
) : BufferFactory by delegate {
    override fun allocate(size: Int, byteOrder: ByteOrder): PlatformBuffer {
        metrics.recordAllocation(size)
        return delegate.allocate(size, byteOrder)
    }
}
```

## Library Author Pattern

Accept a factory parameter so callers can control allocation:

```kotlin
class MqttConnection(
    val factory: BufferFactory = BufferFactory.Default,
) {
    fun send(packet: MqttPacket) {
        factory.allocate(bufferSize).use { buffer ->
            packet.writeTo(buffer)
        }
    }
}
```

## Platform-Specific Notes

### Android SharedMemory

All `JvmBuffer`s are `Parcelable`. For zero-copy IPC:

```kotlin
// In your Service or ContentProvider
val buffer = BufferFactory.shared().allocate(1024)
buffer.writeBytes(data)
buffer.resetForRead()

// Pass through Binder
parcel.writeParcelable(buffer as Parcelable, 0)
```

### JavaScript SharedArrayBuffer

Requires CORS headers. Add to `webpack.config.d/`:

```javascript
if (config.devServer != null) {
    config.devServer.headers = {
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Embedder-Policy": "require-corp"
    }
}
```

Without proper headers, falls back to regular `ArrayBuffer`.

## Choosing a Factory

| Use Case | Recommended Factory |
|----------|---------------------|
| Network I/O | `Default` |
| File I/O | `Default` |
| Short-lived parsing | `managed()` or pool from `Default` |
| Android IPC | `shared()` |
| Multi-threaded JS | `shared()` |
| Deterministic cleanup | `Deterministic` |
| General purpose | `Default` |
