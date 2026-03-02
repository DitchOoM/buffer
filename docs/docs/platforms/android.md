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

### Memory Lifecycle

Android's `DirectJvmBuffer` is GC-managed — no explicit cleanup needed. Calling
`freeNativeMemory()` on an Android `DirectJvmBuffer` is a no-op.

Unlike the JVM (which allocates direct buffers with `Unsafe.allocateMemory()` and can
free them early via `Unsafe.invokeCleaner()`), Android's `DirectByteBuffer` is backed by
a non-movable byte array allocated through
[`VMRuntime.newNonMovableArray()`](https://cs.android.com/android/platform/superproject/+/android-16.0.0_r2:libcore/ojluni/src/main/java/java/nio/DirectByteBuffer.java;l=73).
This memory is owned by the GC — there is no public API to free it deterministically.
The [`MemoryRef.free()`](https://cs.android.com/android/platform/superproject/+/android-16.0.0_r2:libcore/ojluni/src/main/java/java/nio/DirectByteBuffer.java;l=90)
method simply nulls the backing array reference and lets the GC reclaim it. The Android
source itself
[notes](https://cs.android.com/android/platform/superproject/+/android-16.0.0_r2:libcore/ojluni/src/main/java/java/nio/DirectByteBuffer.java;l=103):
*"Only have references to java objects, no need for a cleaner since the GC will do all
the work."* This has not changed from API 28 through API 36.

While Android does expose
[`Unsafe.freeMemory(long)`](https://developer.android.com/reference/sun/misc/Unsafe#freeMemory(long)),
it can only free memory allocated via `Unsafe.allocateMemory()` — not `DirectByteBuffer`
memory from `VMRuntime.newNonMovableArray()`. Android does **not** expose
`Unsafe.invokeCleaner(ByteBuffer)` (a JDK 9+ addition).

For deterministic native memory management on Android, use `withScope {}` (backed by
`UnsafeBufferScope`, which allocates via `Unsafe.allocateMemory()` /
`Unsafe.freeMemory()` — a separate allocation path from `ByteBuffer.allocateDirect()`).

### Zero-Copy Direct Buffers

On Android, `DirectByteBuffer` is backed by a non-movable `byte[]` — the native address
and the backing array point to the **same memory**. Android's own
[source confirms](https://cs.android.com/android/platform/superproject/+/android-16.0.0_r2:libcore/ojluni/src/main/java/java/nio/ByteBuffer.java;l=1160):
*"isDirect() doesn't imply !hasArray(), ByteBuffer.allocateDirect allocated buffer will
have a backing, non-gc-movable byte array."*

This means `DirectJvmBuffer` on Android implements **both** `NativeMemoryAccess` and
`ManagedMemoryAccess` — conversions like `toByteArray()` and `toNativeData()` are
zero-copy when the buffer is already direct.

This differs from the JVM, where direct buffers are off-heap (`hasArray() = false`) and
any conversion to `ByteArray` requires a copy.

**Why do JNI/Camera2/MediaCodec require direct buffers?**

"Direct" on Android means **pinned/non-movable**, not off-heap. A regular `byte[]` can
be relocated by the GC at any time. Native code (camera HAL, video codecs, OpenGL) needs
a stable pointer that won't move mid-operation, which only `newNonMovableArray` guarantees.

```kotlin
// Large buffers should use Direct to avoid GC pressure from relocation
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
