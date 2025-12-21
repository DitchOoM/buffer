# Buffer Performance Analysis

This document summarizes benchmark results and optimization strategies for the buffer library across all platforms.

## Benchmark Results Summary

### JVM Platform (Optimal Baseline)

| Operation | Throughput | Notes |
|-----------|------------|-------|
| allocate (1KB) | 2.36B ops/s | TLAB + JIT optimized |
| slice | 2.32B ops/s | Zero-copy ByteBuffer.slice() |
| websocketParseFrame | 316M ops/s | Efficient multi-byte reads |
| mqttReadVariableInt | 353M ops/s | Simple bit operations |
| writeAndReadAllPrimitives | 190M ops/s | Direct ByteBuffer access |
| binaryBulkRead (1KB) | 28M ops/s | Array copy |
| httpReadHeaders | 9.4M ops/s | Byte-by-byte |
| httpScanForCrLf | 6.6M ops/s | Byte scan |
| websocketUnmaskPayload | 824K ops/s | XOR unmasking (bottleneck) |
| binaryScanForMarker | 251K ops/s | Byte-by-byte 4KB scan |

### Native Platform (macOS ARM64)

| Operation | Throughput | vs JVM |
|-----------|------------|--------|
| allocate (1KB) | 3.82M ops/s | 617x slower |
| allocateSmallBuffer (64B) | 4.76M ops/s | 486x slower |
| allocateMediumBuffer (4KB) | 2.43M ops/s | 975x slower |
| allocateLargeBuffer (64KB) | 741K ops/s | 3200x slower |
| readAllPrimitivesFromFragmented | 4.14M ops/s | 50x slower |

## Why JVM is Faster

1. **Thread-Local Allocation Buffers (TLAB)**: JVM pre-allocates memory per thread, making allocation nearly free
2. **JIT Compilation**: Hot paths compile to single CPU instructions
3. **ByteBuffer Intrinsics**: Direct memory access via `sun.misc.Unsafe`

## Why Native is Slower for Allocation

1. **malloc() overhead**: Each allocation requires a system call or heap traversal
2. **No TLAB equivalent**: Every allocation goes through the global allocator
3. **Larger buffers are worse**: mmap() may be triggered for large allocations

## Platform Implementations

### JVM/Android
- Uses `java.nio.ByteBuffer` with direct delegation
- Zero-copy slicing via `ByteBuffer.slice()`
- Bytecode: ~7 bytes per primitive read (direct invokevirtual)

### JS (Browser/Node.js)
- Uses `Int8Array` with cached `DataView`
- Zero-copy slicing via `Int8Array` view constructor
- Long operations use two 32-bit reads (no allocation)

### Native (macOS/Linux)
- Uses `UnsafeMemory` with malloc/free via kotlinx.cinterop
- Zero-copy slicing via offset tracking (same memory address)
- Byte swapping via bitwise operations when needed

## Optimization Recommendations

### 1. Use Buffer Pooling (Critical for Native)
```kotlin
val pool = BufferPool.unsafe(maxSize = 1024, maxBuffers = 100)
pool.use { buffer ->
    // Use buffer
}
```

### 2. Prefer Bulk Operations
```kotlin
// Fast: bulk read
val chunk = buffer.readByteArray(1024)

// Slow: byte-by-byte
for (i in 0 until 1024) {
    buffer.readByte()
}
```

### 3. WebSocket XOR Unmasking Optimization
The current byte-by-byte XOR unmasking is a bottleneck. For high-performance scenarios:
```kotlin
// Slow: 824K ops/s
for (i in 0 until payloadLen) {
    val masked = buffer.readByte()
    result[i] = (masked.toInt() xor mask[i % 4].toInt()).toByte()
}

// Faster: Process 4 bytes at a time
val maskInt = (mask[0].toInt() and 0xFF shl 24) or
              (mask[1].toInt() and 0xFF shl 16) or
              (mask[2].toInt() and 0xFF shl 8) or
              (mask[3].toInt() and 0xFF)
while (remaining >= 4) {
    val value = buffer.readInt()
    buffer.writeInt(value xor maskInt)
}
```

### 4. Use Slices for Zero-Copy Parsing
```kotlin
// Zero-copy: create a view
val header = buffer.slice()

// Avoid: copying to new buffer
val header = buffer.readByteArray(headerLen)
```

## Bottlenecks and Solutions

| Bottleneck | Current | Solution |
|------------|---------|----------|
| WebSocket unmasking | 824K ops/s | Process 4/8 bytes at a time |
| Binary marker scan | 251K ops/s | Use platform-specific SIMD if available |
| Native allocation | 3.8M ops/s | Use buffer pooling |

## Running Benchmarks

```bash
# JVM benchmarks
./gradlew jvmTestBenchmark

# Native benchmarks (macOS ARM64)
./gradlew macosArm64TestBenchmark

# JS benchmarks
./gradlew jsTestBenchmark
```

## Conclusion

- **JVM is highly optimized** - use it when maximum performance is required
- **Native allocation is the main bottleneck** - always use buffer pooling
- **All platforms have zero-copy slicing** - leverage this for parsing
- **Bulk operations are faster** - avoid byte-by-byte when possible
