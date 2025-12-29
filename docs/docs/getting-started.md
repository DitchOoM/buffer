---
sidebar_position: 2
title: Getting Started
---

# Getting Started

## Installation

See [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/buffer) for the latest version.

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.ditchoom:buffer:<latest-version>'
}
```

For multiplatform projects, add to your `commonMain` dependencies:

```kotlin
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("com.ditchoom:buffer:<latest-version>")
            }
        }
    }
}
```

## Your First Buffer

### Allocating a Buffer

```kotlin
import com.ditchoom.buffer.PlatformBuffer

// Allocate 1024 bytes
val buffer = PlatformBuffer.allocate(1024)
```

### Writing Data

```kotlin
// Write primitives (advances position automatically)
buffer.writeByte(0x42)
buffer.writeShort(1000)
buffer.writeInt(123456)
buffer.writeLong(9876543210L)
buffer.writeFloat(3.14f)
buffer.writeDouble(2.71828)

// Write strings
buffer.writeString("Hello, World!")

// Write byte arrays
buffer.writeBytes(byteArrayOf(1, 2, 3, 4))
```

### Preparing to Read

After writing, call `resetForRead()` to set position to 0 and limit to the written amount:

```kotlin
buffer.resetForRead()
```

### Reading Data

```kotlin
val byte = buffer.readByte()
val short = buffer.readShort()
val int = buffer.readInt()
val long = buffer.readLong()
val float = buffer.readFloat()
val double = buffer.readDouble()
val text = buffer.readString(13)  // "Hello, World!"
val bytes = buffer.readByteArray(4)
```

## Wrapping Existing Data

```kotlin
// Wrap a byte array (no copy)
val data = byteArrayOf(0x00, 0x01, 0x02, 0x03)
val buffer = PlatformBuffer.wrap(data)

// Now read from it
buffer.readInt()  // 0x00010203 (big-endian)
```

## Allocation Zones

Choose where the buffer is allocated:

```kotlin
import com.ditchoom.buffer.AllocationZone

// Heap allocation (GC managed)
val heapBuffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)

// Direct/off-heap (default, zero-copy I/O)
val directBuffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)

// Shared memory (for IPC on Android)
val sharedBuffer = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)
```

See [Allocation Zones](./core-concepts/allocation-zones) for details.

## Byte Order

Specify endianness:

```kotlin
import com.ditchoom.buffer.ByteOrder

// Big-endian (network byte order, default)
val bigEndian = PlatformBuffer.allocate(1024, byteOrder = ByteOrder.BIG_ENDIAN)

// Little-endian
val littleEndian = PlatformBuffer.allocate(1024, byteOrder = ByteOrder.LITTLE_ENDIAN)
```

## Buffer Pooling

For high-performance scenarios, use buffer pools to avoid allocation overhead:

```kotlin
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.pool.withBuffer

// Use withPool for scoped pool lifetime
withPool(defaultBufferSize = 8192) { pool ->
    // Use withBuffer for automatic acquire/release
    pool.withBuffer(1024) { buffer ->
        buffer.writeInt(42)
        buffer.resetForRead()
        buffer.readInt()
    } // Buffer automatically returned to pool
} // Pool automatically cleared
```

See [Buffer Pooling](./recipes/buffer-pooling) for more patterns.

## Next Steps

- [Buffer Basics](./core-concepts/buffer-basics) - Position, limit, capacity
- [Basic Operations](./recipes/basic-operations) - Read/write patterns
- [Stream Processing](./recipes/stream-processing) - Handle chunked data
