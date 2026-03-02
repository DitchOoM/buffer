---
sidebar_position: 1
title: JVM
---

# JVM Platform

Buffer on JVM wraps `java.nio.ByteBuffer` for optimal performance.

## Implementation

| Zone | JVM < 21 | JVM 21+ |
|------|----------|---------|
| `Heap` | `HeapJvmBuffer` | `HeapJvmBuffer` |
| `Direct` | `DirectJvmBuffer` | `FfmBuffer` (FFM Arena) |
| `SharedMemory` | Falls back to Direct | Falls back to Direct |

## JVM Flags

The library's `Automatic-Module-Name` is `com.ditchoom.buffer`. This lets you grant permissions to the library specifically instead of using `ALL-UNNAMED`.

### JDK 21+ (FFM)

Direct and SharedMemory buffers use the FFM API (`MemorySegment.reinterpret()`), which is a restricted method. You must enable native access:

```
--enable-native-access=com.ditchoom.buffer
```

Without this flag, JDK 22-23 print warnings and JDK 24+ throws `IllegalCallerException`.

### JDK 9-20 (Reflection)

`nativeAddress` access uses reflection into `java.nio.Buffer.address`. This is recommended but not strictly required (the library falls back gracefully):

```
--add-opens=java.base/java.nio=com.ditchoom.buffer
```

### JDK 8

No flags needed (no module system).

### Gradle Configuration

```kotlin
// build.gradle.kts
tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "--enable-native-access=com.ditchoom.buffer",
        "--add-opens=java.base/java.nio=com.ditchoom.buffer",
    )
}

// For test tasks
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--enable-native-access=com.ditchoom.buffer",
        "--add-opens=java.base/java.nio=com.ditchoom.buffer",
    )
}
```

:::note Classpath Usage
If you run the library from the classpath (unnamed module) rather than the module path, use `ALL-UNNAMED` instead of `com.ditchoom.buffer`.
:::

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

## Memory Management

Direct buffer memory cleanup varies by JVM version:

| JVM Version | Cleanup Behavior |
|-------------|-----------------|
| JVM 21+ | `FfmBuffer` uses FFM `Arena.ofShared()` — deterministic free via `freeNativeMemory()` |
| JVM 9-20 | `DirectJvmBuffer` uses `Unsafe.invokeCleaner()` — best-effort deterministic free (falls back to GC) |
| JVM 8 | GC-only (no deterministic free) |

Recommended patterns:

```kotlin
// 1. use block (recommended)
PlatformBuffer.allocate(1024, AllocationZone.Direct).use { buffer ->
    buffer.writeInt(42)
}

// 2. Pool (recommended for repeated allocations)
withPool { pool ->
    pool.withBuffer(1024) { buffer ->
        buffer.writeInt(42)
    }
}

// 3. Explicit cleanup
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
try {
    buffer.writeInt(42)
} finally {
    buffer.freeNativeMemory()
}
```

## FFM Interop (JVM 21+)

On JVM 21+, direct buffers are backed by FFM `Arena`/`MemorySegment`. Use `asMemorySegment()` for Panama downcalls:

```kotlin
val segment = buffer.asMemorySegment() ?: error("No native memory")
val result = nativeFunctionHandle.invokeExact(segment, buffer.remaining()) as Int
```

## Native Memory Access

Direct buffers expose `nativeAddress` and `nativeSize` for JNI/FFI interop:

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
val address = (buffer as NativeMemoryAccess).nativeAddress
val size = buffer.nativeSize
// Pass address and size to JNI/FFI functions
```

## Best Practices

1. **Use Direct for I/O** - network sockets, file channels
2. **Use Heap for parsing** - or pool Direct buffers
3. **Pool Direct buffers** - allocation is expensive
4. **Watch off-heap memory** - not tracked by `-Xmx`
5. **Call `freeNativeMemory()`** - or use `use {}` blocks to free direct buffers deterministically
