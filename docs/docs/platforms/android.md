---
sidebar_position: 2
title: Android
---

# Android Platform

Android extends JVM support with `SharedMemory` for zero-copy IPC.

## Implementation

| Zone | Android Type |
|------|--------------|
| `Heap` | `HeapByteBuffer` |
| `Direct` | `DirectByteBuffer` |
| `SharedMemory` | `SharedMemory` (API 27+) or `ParcelFileDescriptor` pipes |

## Parcelable Support

All `JvmBuffer` instances are `Parcelable`:

```kotlin
// In your Service
val buffer = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)
buffer.writeBytes(imageData)
buffer.resetForRead()

// Send via Binder
val intent = Intent(context, MyService::class.java)
intent.putExtra("buffer", buffer as Parcelable)
startService(intent)

// Receive in Service
val buffer = intent.getParcelableExtra<PlatformBuffer>("buffer")
val data = buffer?.readByteArray(buffer.remaining())
```

## SharedMemory (API 27+)

Zero-copy IPC using Android's SharedMemory:

```kotlin
// Create shared buffer
val buffer = PlatformBuffer.allocate(
    size = 1024 * 1024,  // 1MB
    zone = AllocationZone.SharedMemory
)

// Write data
buffer.writeBytes(largeData)
buffer.resetForRead()

// Pass through Binder - no copy!
parcel.writeParcelable(buffer as Parcelable, 0)
```

## Pre-API 27 Fallback

On older devices, uses `ParcelFileDescriptor` pipes:

```kotlin
// Same API, but data is piped through file descriptors
val buffer = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)
// Works on all Android versions, but with copy overhead on old devices
```

## Memory Considerations

### Direct Buffers

```kotlin
// Large buffers should use Direct to avoid GC
val videoFrame = PlatformBuffer.allocate(
    size = 1920 * 1080 * 4,  // 8MB RGBA
    zone = AllocationZone.Direct
)
```

### Buffer Pooling for Camera/Video

```kotlin
class CameraProcessor {
    private val pool = BufferPool(
        threadingMode = ThreadingMode.MultiThreaded,
        maxPoolSize = 8,  // Match camera buffer count
        defaultBufferSize = 1920 * 1080 * 4,
        allocationZone = AllocationZone.Direct
    )

    fun onFrameAvailable(frame: ByteArray) {
        pool.withBuffer(frame.size) { buffer ->
            buffer.writeBytes(frame)
            buffer.resetForRead()
            processFrame(buffer)
        }
    }
}
```

## ContentProvider Integration

```kotlin
class DataProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val buffer = extras?.getParcelable<PlatformBuffer>("data")
        // Process buffer...

        val result = Bundle()
        result.putParcelable("result", responseBuffer as Parcelable)
        return result
    }
}
```

## Best Practices

1. **Use SharedMemory for IPC** - zero-copy between processes
2. **Pool camera buffers** - avoid allocation during capture
3. **Direct for large media** - video frames, images
4. **Watch memory limits** - Android kills background apps aggressively
