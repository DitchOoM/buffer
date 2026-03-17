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
    // Core buffer library
    implementation("com.ditchoom:buffer:<latest-version>")

    // Optional: Protocol codecs (Codec<T> for ReadBuffer/WriteBuffer)
    implementation("com.ditchoom:buffer-codec:<latest-version>")

    // Optional: Compression (gzip, deflate)
    implementation("com.ditchoom:buffer-compression:<latest-version>")

    // Optional: Flow extensions (lines, mapBuffer, asStringFlow)
    implementation("com.ditchoom:buffer-flow:<latest-version>")
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

```kotlin
import com.ditchoom.buffer.BufferFactory

val buffer = BufferFactory.Default.allocate(1024)
buffer.writeInt(42)
buffer.writeString("Hello, Buffer!")
buffer.resetForRead()

val number = buffer.readInt()      // 42
val text = buffer.readString(14)   // "Hello, Buffer!"
```

## Buffer Pooling

Allocate once, reuse for every request — no GC pauses:

```kotlin
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.pool.withBuffer

withPool(defaultBufferSize = 8192) { pool ->
    pool.withBuffer(1024) { buffer ->
        buffer.writeInt(42)
        buffer.resetForRead()
        buffer.readInt()
    } // buffer returned to pool
} // pool cleared
```

See [Buffer Pooling](./recipes/buffer-pooling) for threading modes, statistics, and production patterns.

## Stream Processing

Parse protocols that span chunk boundaries — no manual accumulator code:

```kotlin
import com.ditchoom.buffer.stream.StreamProcessor

val processor = StreamProcessor.create(pool)
processor.append(networkChunk1)
processor.append(networkChunk2)

while (processor.available() >= 4) {
    val length = processor.peekInt()
    if (processor.available() < 4 + length) break
    processor.skip(4)
    val payload = processor.readBuffer(length)
    handleMessage(payload)
}
processor.release()
```

See [Stream Processing](./recipes/stream-processing) for auto-filling, decompression transforms, and the builder API.

## Protocol Codecs

Annotate a data class, get a type-safe codec at compile time:

```kotlin
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.LengthPrefixed

@ProtocolMessage
data class DeviceReport(
    val protocolVersion: UByte,       // 1 byte
    val deviceType: UShort,           // 2 bytes
    val sequenceNumber: UInt,         // 4 bytes
    val timestamp: Long,              // 8 bytes
    val latitude: Double,             // 8 bytes
    val longitude: Double,            // 8 bytes
    val altitude: Float,              // 4 bytes
    val batteryLevel: UByte,          // 1 byte
    val signalStrength: Short,        // 2 bytes
    @LengthPrefixed val deviceName: String,
)

// Generated codec — batch-optimized, with round-trip testing
val buffer = DeviceReportCodec.encodeToBuffer(report)
val decoded = DeviceReportCodec.decode(buffer)
DeviceReportCodec.testRoundTrip(report)
```

No manual read/write matching for 10 fields. No sizeOf arithmetic. No field-order bugs.

See [Protocol Codecs](./recipes/protocol-codecs) for annotations, sealed dispatch, value classes, and the SPI.

## Compression

Compress and decompress any `ReadBuffer` — works on all platforms:

```kotlin
import com.ditchoom.buffer.compression.*

val data = "Hello, World!".toReadBuffer()
val compressed = compress(data, CompressionAlgorithm.Gzip).getOrThrow()
val decompressed = decompress(compressed, CompressionAlgorithm.Gzip).getOrThrow()
```

See [Compression](./recipes/compression) for streaming compression, algorithms, and integration with `StreamProcessor`.

## Flow Extensions

Compose streaming transforms with Kotlin Flow:

```kotlin
import com.ditchoom.buffer.flow.*

bufferFlow
    .mapBuffer { decompress(it, Gzip).getOrThrow() }
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

---

## Buffer Factories

Choose how buffers are allocated:

```kotlin
// Platform-optimal native memory (recommended)
val buffer = BufferFactory.Default.allocate(1024)

// GC-managed heap memory
val heapBuffer = BufferFactory.managed().allocate(1024)

// Cross-process shared memory (for IPC on Android)
val sharedBuffer = BufferFactory.shared().allocate(1024)

// Deterministic cleanup (explicit free, no GC dependency)
BufferFactory.deterministic().allocate(1024).use { buf ->
    buf.writeInt(42)
} // freed immediately
```

Layer behaviors with composable decorators:

```kotlin
val factory = BufferFactory.Default
    .requiring<NativeMemoryAccess>()   // throw if buffer lacks native access
    .withSizeLimit(1_048_576)          // cap allocation size
    .withPooling(pool)                 // recycle buffers
```

See [Buffer Factories](./core-concepts/allocation-zones) for full details.

## Wrapping Existing Data

```kotlin
val data = byteArrayOf(0x00, 0x01, 0x02, 0x03)
val buffer = BufferFactory.Default.wrap(data)
buffer.readInt()  // 0x00010203 (big-endian)
```

## Reading and Writing Primitives

```kotlin
val buffer = BufferFactory.Default.allocate(1024)

// Write (advances position automatically)
buffer.writeByte(0x42)
buffer.writeShort(1000)
buffer.writeInt(123456)
buffer.writeLong(9876543210L)
buffer.writeFloat(3.14f)
buffer.writeDouble(2.71828)
buffer.writeString("Hello, World!")

buffer.resetForRead()

// Read
val byte = buffer.readByte()
val short = buffer.readShort()
val int = buffer.readInt()
val long = buffer.readLong()
val float = buffer.readFloat()
val double = buffer.readDouble()
val text = buffer.readString(13)
```

See [Basic Operations](./recipes/basic-operations) for absolute reads/writes, slicing, and byte arrays.

## Searching and Comparing

```kotlin
val buffer = BufferFactory.Default.allocate(100)
buffer.writeString("Hello, World!")
buffer.resetForRead()

buffer.indexOf(','.code.toByte())  // 5
buffer.indexOf("World")            // 7
buffer.indexOf(0x1234.toShort(), aligned = true)  // SIMD-accelerated

val buf1 = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3))
val buf2 = BufferFactory.Default.wrap(byteArrayOf(1, 2, 5))
buf1.contentEquals(buf2)  // false
buf1.mismatch(buf2)       // 2
```

## Byte Order

Default is `ByteOrder.BIG_ENDIAN`. Specify when needed:

```kotlin
val bigEndian = BufferFactory.Default.allocate(1024, ByteOrder.BIG_ENDIAN)
val littleEndian = BufferFactory.Default.allocate(1024, ByteOrder.LITTLE_ENDIAN)
```

See [Byte Order](./core-concepts/byte-order) for protocol-specific guidance.

## Next Steps

- [Buffer Pooling](./recipes/buffer-pooling) - Eliminate allocation overhead
- [Stream Processing](./recipes/stream-processing) - Handle chunked network data
- [Protocol Codecs](./recipes/protocol-codecs) - Type-safe encode/decode
- [Buffer Basics](./core-concepts/buffer-basics) - Position, limit, capacity internals
