---
sidebar_position: 2
title: Allocation Zones
---

# Allocation Zones

Allocation zones determine where buffer memory is allocated and affect performance, IPC capabilities, and garbage collection behavior.

## Overview

```kotlin
import com.ditchoom.buffer.AllocationZone

val buffer = PlatformBuffer.allocate(
    size = 1024,
    zone = AllocationZone.Direct  // Choose your zone
)
```

## Zone Types

### AllocationZone.Heap

Memory allocated on the JVM heap (or equivalent on other platforms).

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)
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

### AllocationZone.Direct

Memory allocated outside the JVM heap (off-heap).

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
```

**Characteristics:**
- Not managed by garbage collector
- Zero-copy I/O with native code
- Best for network I/O and file operations
- Default allocation zone

**Platform behavior:**
| Platform | Implementation |
|----------|---------------|
| JVM | [`DirectByteBuffer`](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html#allocateDirect-int-) |
| Android | [`DirectByteBuffer`](https://developer.android.com/reference/java/nio/ByteBuffer#allocateDirect(int)) |
| iOS/macOS | [`NSMutableData`](https://developer.apple.com/documentation/foundation/nsmutabledata) |
| JavaScript | [`Int8Array`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Int8Array) |
| WASM | `LinearBuffer` (native WASM memory) |
| Linux | `ByteArrayBuffer` |

![Heap vs Direct Memory](/img/heap-vs-direct.svg)

### AllocationZone.SharedMemory

Memory that can be shared across process boundaries.

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)
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
| WASM | `LinearBuffer` (falls back to Direct) |
| Others | Falls back to Direct |

![SharedMemory IPC](/img/shared-memory-ipc.svg)

## Platform-Specific Notes

### Android SharedMemory

All `JvmBuffer`s are `Parcelable`. For zero-copy IPC:

```kotlin
// In your Service or ContentProvider
val buffer = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)
buffer.writeBytes(data)

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

## Choosing a Zone

| Use Case | Recommended Zone |
|----------|-----------------|
| Network I/O | `Direct` |
| File I/O | `Direct` |
| Short-lived parsing | `Heap` or pool from `Direct` |
| Android IPC | `SharedMemory` |
| Multi-threaded JS | `SharedMemory` |
| General purpose | `Direct` (default) |
