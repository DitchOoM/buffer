---
sidebar_position: 2
title: Android
---

# Android Platform

Android extends JVM support with `SharedMemory` for zero-copy IPC.

## Implementation

| Factory | Android Type |
|---------|--------------|
| `managed()` | `HeapByteBuffer` |
| `Default` | `DirectByteBuffer` |
| `shared()` | `SharedMemory` (API 27+) or `ParcelFileDescriptor` pipes |

## Parcelable Support

All `JvmBuffer` instances are `Parcelable`:

```kotlin
// In your Service
val buffer = BufferFactory.shared().allocate(1024)
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
val buffer = BufferFactory.shared().allocate(1024 * 1024)  // 1MB

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
val buffer = BufferFactory.shared().allocate(1024)
// Works on all Android versions, but with copy overhead on old devices
```

## Memory Considerations

### Direct Buffers

```kotlin
// Large buffers should use Direct to avoid GC
val videoFrame = BufferFactory.Default.allocate(1920 * 1080 * 4)  // 8MB RGBA
```

:::warning Avoid repeated ad hoc large allocations
On Android, `ByteBuffer.allocateDirect()` backs the buffer with a pinned, non-movable
array in the ART heap. Repeatedly allocating and dropping large, oddly-sized buffers
(e.g. one per camera frame) can fragment ART's Large Object Space and non-moving
space until a same-size allocation fails with an `OutOfMemoryError`, even though the
heap has plenty of nominally free bytes. This mostly affects stock (non-Mainline) ART
images under sustained mixed-size churn. See [Android ART Memory & OOM
Recovery](../core-concepts/android-art-memory.md) for the platform comparison and the pool's
built-in recovery net, and `ANDROID_ART_ALLOCATOR.md` for the full repro and root cause.

Prefer a shared, pooled `BufferPool` (below) over one-off
`BufferFactory.Default.allocate()` calls for repeated large camera/video buffers —
the pool rounds requests into size-class buckets and reuses buffers instead of
churning the allocator with new odd-sized ones.
:::

### Buffer Pooling for Camera/Video

```kotlin
class CameraProcessor {
    private val pool = BufferPool(
        threadingMode = ThreadingMode.MultiThreaded,
        maxPoolSize = 8,  // Match camera buffer count
        defaultBufferSize = 1920 * 1080 * 4,
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
