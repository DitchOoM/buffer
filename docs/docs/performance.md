---
sidebar_position: 10
title: Performance
---

# Performance Guide

Tips for maximizing Buffer performance across platforms.

## Key Principles

1. **Avoid allocations in hot paths** - use buffer pools
2. **Prefer zero-copy operations** - slicing over copying
3. **Use largest primitives** - `readLong()` over 8x `readByte()`
4. **Use bulk operations** - multi-byte reads/writes
5. **Choose the right allocation zone** - Direct for I/O

## Buffer Pooling

Allocation is expensive. Pool buffers in hot paths:

```kotlin
// Bad: allocate per request
fun handleRequest(data: ByteArray) {
    val buffer = PlatformBuffer.allocate(data.size)  // Allocation!
    buffer.writeBytes(data)
    process(buffer)
}

// Good: pool buffers
val pool = BufferPool(defaultBufferSize = 8192)

fun handleRequest(data: ByteArray) {
    pool.withBuffer(data.size) { buffer ->
        buffer.writeBytes(data)
        process(buffer)
    }  // Returned to pool
}
```

## Zero-Copy Operations

### Slicing

Create views without copying:

```kotlin
// Zero-copy: slice shares memory
val slice = buffer.slice()

// Copy: creates new buffer
val copy = PlatformBuffer.allocate(buffer.remaining())
copy.write(buffer)
```

### StreamProcessor Zero-Copy

StreamProcessor returns slices when data is contiguous:

```kotlin
// Zero-copy when data is in single chunk
val payload = processor.readBuffer(length)
// Returns slice if possible, copies only when spanning chunks
```

## Use Largest Primitives

Reading/writing larger primitives is significantly faster than byte-by-byte operations. The CPU processes 8 bytes in a single `Long` operation vs 8 separate `Byte` operations.

### WebSocket XOR Masking Example

WebSocket requires XOR masking of payload data. The mask is always exactly 4 bytes, so use `Int` instead of `ByteArray`:

```kotlin
// Slow: XOR each byte individually with ByteArray mask
fun maskPayloadSlow(payload: PlatformBuffer, maskKey: ByteArray) {
    var i = 0
    while (payload.remaining() > 0) {
        val b = payload.readByte()
        payload.position(payload.position() - 1)
        payload.writeByte((b.toInt() xor maskKey[i % 4].toInt()).toByte())
        i++
    }
}

// Fast: Use Int for mask, expand to Long, process 8 bytes at a time
fun maskPayload(payload: PlatformBuffer, maskKey: Int) {
    // Expand 4-byte Int mask to 8-byte Long mask by duplicating
    val maskLong = (maskKey.toLong() and 0xFFFFFFFFL) or
                   ((maskKey.toLong() and 0xFFFFFFFFL) shl 32)

    // Process 8 bytes at a time
    while (payload.remaining() >= 8) {
        val value = payload.readLong()
        payload.position(payload.position() - 8)
        payload.writeLong(value xor maskLong)
    }

    // Handle remaining 0-7 bytes
    var shift = 24
    while (payload.remaining() > 0) {
        val b = payload.readByte()
        val maskByte = ((maskKey ushr shift) and 0xFF).toByte()
        payload.position(payload.position() - 1)
        payload.writeByte((b.toInt() xor maskByte.toInt()).toByte())
        shift = (shift - 8) and 31  // Wrap around: 24 -> 16 -> 8 -> 0 -> 24...
    }
}

// Read mask as Int directly from buffer instead of ByteArray
val maskKey = buffer.readInt()  // Not buffer.readBytes(4)
```

**Why this is faster:**
- `Int` mask avoids array indexing and bounds checks
- Expanding `Int` to `Long` is a single bitwise operation
- Processing 8 bytes per iteration instead of 1
- Remaining 0-7 bytes use bit shifts on the `Int` mask (no array access)

### General Pattern: Largest to Smallest

Process data using the largest primitive that fits, then fall back to smaller ones:

```kotlin
fun processBuffer(buffer: ReadBuffer) {
    // Process 8 bytes at a time
    while (buffer.remaining() >= 8) {
        val value = buffer.readLong()
        process8Bytes(value)
    }

    // Process 4 bytes at a time
    while (buffer.remaining() >= 4) {
        val value = buffer.readInt()
        process4Bytes(value)
    }

    // Process remaining bytes
    while (buffer.remaining() > 0) {
        val value = buffer.readByte()
        process1Byte(value)
    }
}
```

> **Note:** Future releases will add SIMD optimizations for even faster bulk operations on supported platforms.

## Bulk Operations

Multi-byte operations are faster than byte-by-byte:

```kotlin
// Slow: byte-by-byte
for (b in bytes) {
    buffer.writeByte(b)
}

// Fast: bulk write
buffer.writeBytes(bytes)

// Also fast: buffer-to-buffer
destBuffer.write(sourceBuffer)
```

## Platform-Specific Tips

### JVM

- Use `Direct` for NIO channel I/O
- Pool Direct buffers (allocation is slow)
- Heap buffers require copying for native I/O

### Android

- Use `SharedMemory` for IPC
- Pool camera/video frame buffers
- Watch memory pressure on low-end devices

### JavaScript

- Configure CORS for SharedArrayBuffer
- Pool for WebSocket message handling
- Batch operations to reduce JS interop

### Native (Linux/Apple)

- Buffer pooling is critical
- Direct memory access is fast once allocated

### WASM

- **Use `Direct` for JS interop** - LinearBuffer shares memory with JavaScript
- **Use `Heap` for compute workloads** - ByteArrayBuffer has no memory limits
- **LinearBuffer is faster** - 25% faster single ops, 2x faster bulk ops
- **Pre-allocated memory** - 256MB limit due to optimizer bug workaround

```kotlin
// JS interop: use Direct (LinearBuffer)
val interopBuffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)

// Compute workloads: use Heap (ByteArrayBuffer)
val computeBuffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)
```

WASM benchmark results:

| Operation | LinearBuffer | ByteArrayBuffer | Speedup |
|-----------|-------------|-----------------|---------|
| Single int ops | 91.1M ops/s | 73.2M ops/s | 1.24x |
| Bulk ops (256 ints) | 2.0M ops/s | 967K ops/s | 2.04x |

## Profiling Tips

### Measure Allocation Rate

```kotlin
val stats = pool.stats()
val hitRate = stats.poolHits.toDouble() / (stats.poolHits + stats.poolMisses)
println("Pool hit rate: ${hitRate * 100}%")

// Target: >90% hit rate
```

### Benchmark Critical Paths

For accurate performance measurements, use proper benchmarking frameworks:

- **JVM/Multiplatform**: [kotlinx-benchmark](https://github.com/Kotlin/kotlinx-benchmark)
- **Android**: [Jetpack Benchmark](https://developer.android.com/topic/performance/benchmarking/microbenchmark-overview)

```kotlin
// Using kotlinx-benchmark
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
class BufferBenchmark {
    private lateinit var pool: BufferPool

    @Setup
    fun setup() {
        pool = BufferPool(defaultBufferSize = 1024)
    }

    @Benchmark
    fun pooledBufferReadWrite() {
        pool.withBuffer(1024) { buffer ->
            buffer.writeInt(42)
            buffer.resetForRead()
            buffer.readInt()
        }
    }
}
```

:::warning Avoid Simple Timing
`measureTimeMillis` in a loop doesn't account for JVM warmup, GC pauses, or inlining. Use a proper benchmarking library for reliable results.
:::

## Common Anti-Patterns

### Unnecessary Copies

```kotlin
// Anti-pattern: copy to ByteArray then wrap
val bytes = buffer.readByteArray(length)
val newBuffer = PlatformBuffer.wrap(bytes)

// Better: slice directly
val slice = buffer.readBytes(length)
```

### Allocation in Loops

```kotlin
// Anti-pattern
repeat(1000) {
    val buffer = PlatformBuffer.allocate(1024)
    // ...
}

// Better: pool or reuse
val buffer = PlatformBuffer.allocate(1024)
repeat(1000) {
    buffer.resetForWrite()
    // ...
}
```

### Ignoring Position/Limit

```kotlin
// Anti-pattern: reading beyond limit
while (buffer.position() < buffer.capacity()) {
    buffer.readByte()  // May read garbage!
}

// Correct: respect limit
while (buffer.remaining() > 0) {
    buffer.readByte()
}
```

## Summary

| Optimization | Impact | Effort |
|--------------|--------|--------|
| Buffer pooling | High | Low |
| Use largest primitives | High | Low |
| Zero-copy slicing | High | Low |
| Bulk operations | Medium | Low |
| Direct allocation | Medium | Low |
