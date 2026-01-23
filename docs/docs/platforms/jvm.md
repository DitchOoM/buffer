---
sidebar_position: 1
title: JVM
---

# JVM Platform

Buffer on JVM wraps `java.nio.ByteBuffer` for optimal performance.

## Implementation

| Zone | JVM Type |
|------|----------|
| `Heap` | `HeapByteBuffer` |
| `Direct` | `DirectByteBuffer` |
| `SharedMemory` | Falls back to `Direct` |

## Direct vs Heap Buffers

### Direct Buffers (Default)

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
```

- Allocated outside JVM heap via `ByteBuffer.allocateDirect()`
- Zero-copy I/O with NIO channels
- Minimal / No GC overhead
- Best for network and file I/O

### Heap Buffers

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)
```

- Allocated on JVM heap
- Managed by garbage collector
- May require copying for native I/O
- Good for short-lived, small buffers

## Performance Characteristics

```
Operation          | Direct     | Heap
-------------------|------------|------------
Allocation         | Slower     | Faster
First access       | Slower     | Faster
Sustained I/O      | Faster     | Slower (copies)
GC impact          | None       | GC pauses
Memory limit       | OS limit   | -Xmx limit
```

## JNI Considerations

When passing buffers to JNI:

```kotlin
// Direct buffers: zero-copy
val direct = PlatformBuffer.allocate(1024, AllocationZone.Direct)
nativeFunction(direct.asByteBuffer())  // No copy

// Heap buffers: may copy
val heap = PlatformBuffer.allocate(1024, AllocationZone.Heap)
nativeFunction(heap.asByteBuffer())  // JVM may copy to temp direct buffer
```

## Accessing Underlying ByteBuffer

```kotlin
val buffer = PlatformBuffer.allocate(1024) as JvmBuffer
val nioBuffer: ByteBuffer = buffer.byteBuffer

// Use with NIO channels
channel.write(nioBuffer)
```

## Native Data Conversion

Convert buffers to JVM-native `ByteBuffer` for NIO and JNI interop:

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
buffer.writeBytes(data)
buffer.resetForRead()

// Get read-only direct ByteBuffer (zero-copy for direct buffers)
val nativeData = buffer.toNativeData()
val readOnlyBuffer: ByteBuffer = nativeData.byteBuffer
channel.write(readOnlyBuffer)

// Get mutable direct ByteBuffer (zero-copy for direct buffers)
val mutableData = buffer.toMutableNativeData()
val mutableBuffer: ByteBuffer = mutableData.byteBuffer
channel.read(mutableBuffer)
```

### Zero-Copy Behavior

`toNativeData()` and `toMutableNativeData()` always return direct ByteBuffers for native memory access:

| Conversion | Heap Buffer | Direct Buffer |
|------------|-------------|---------------|
| `toNativeData()` | Copy to direct | Zero-copy (duplicate) |
| `toMutableNativeData()` | Copy to direct | Zero-copy (duplicate) |
| `toByteArray()` | Zero-copy (backing array) | Copy required |

:::tip Use Direct for Zero-Copy
For true zero-copy conversion, allocate with `AllocationZone.Direct`. Changes to the returned ByteBuffer will reflect in the original buffer and vice versa.
:::

:::note Position Invariance
All conversion functions operate on remaining bytes (position to limit) and do **not** modify the buffer's position or limit.
:::

See [Platform Interop](../recipes/platform-interop) for more details.

## Best Practices

1. **Use Direct for I/O** - network sockets, file channels
2. **Use Heap for parsing** - or pool Direct buffers
3. **Pool Direct buffers** - allocation is expensive
4. **Watch off-heap memory** - not tracked by `-Xmx`
