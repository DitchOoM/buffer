---
sidebar_position: 4
title: Compression
---

# Compression

The `buffer-compression` module provides cross-platform compression and decompression using Gzip, Deflate, and Raw formats.

## Installation

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-compression:<latest-version>")
}
```

## Quick Start (All Platforms)

These examples work on **every supported platform**, including browser JavaScript.

### Compress Data

```kotlin
import com.ditchoom.buffer.compression.*

suspend fun compressData(data: String): List<ReadBuffer> {
    val output = mutableListOf<ReadBuffer>()

    SuspendingStreamingCompressor.create(CompressionAlgorithm.Gzip).use { compressor ->
        output += compressor.compress(data.toReadBuffer())
        output += compressor.finish()
    }

    return output
}
```

### Decompress Data

```kotlin
suspend fun decompressData(compressed: List<ReadBuffer>): String {
    val output = mutableListOf<ReadBuffer>()

    SuspendingStreamingDecompressor.create(CompressionAlgorithm.Gzip).use { decompressor ->
        for (chunk in compressed) {
            output += decompressor.decompress(chunk)
        }
        output += decompressor.finish()
    }

    // Combine chunks and read as string
    val totalSize = output.sumOf { it.remaining() }
    val result = PlatformBuffer.allocate(totalSize)
    output.forEach { result.write(it) }
    result.resetForRead()
    return result.readString(totalSize)
}
```

### Compress and Send Over Network

```kotlin
suspend fun sendCompressed(socket: Socket, data: String) {
    SuspendingStreamingCompressor.create(CompressionAlgorithm.Gzip).use { compressor ->
        // Compress and send chunks as they're produced
        compressor.compress(data.toReadBuffer()).forEach { chunk ->
            socket.write(chunk)
        }
        // Flush remaining data
        compressor.finish().forEach { chunk ->
            socket.write(chunk)
        }
    }
}
```

### Receive and Decompress from Network

```kotlin
suspend fun receiveDecompressed(socket: Socket): ReadBuffer {
    val output = mutableListOf<ReadBuffer>()

    SuspendingStreamingDecompressor.create(CompressionAlgorithm.Gzip).use { decompressor ->
        while (socket.hasData()) {
            val chunk = socket.read()
            output += decompressor.decompress(chunk)
        }
        output += decompressor.finish()
    }

    // Combine into single buffer
    val totalSize = output.sumOf { it.remaining() }
    val result = PlatformBuffer.allocate(totalSize)
    output.forEach { result.write(it) }
    result.resetForRead()
    return result
}
```

## Compression Algorithms

```kotlin
// Gzip - most common for HTTP, includes headers and CRC
CompressionAlgorithm.Gzip

// Deflate - zlib format with header/trailer
CompressionAlgorithm.Deflate

// Raw - no headers, for custom protocols or when you manage framing yourself
CompressionAlgorithm.Raw
```

## Compression Levels

Control the speed/size tradeoff:

```kotlin
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    level = CompressionLevel.BestSpeed       // Fastest compression
)

SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    level = CompressionLevel.BestCompression // Smallest output
)

SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    level = CompressionLevel.Default         // Balanced (default)
)

SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    level = CompressionLevel.Custom(4)       // Custom 0-9
)
```

## Handling Large Files

For large files, process in chunks to avoid loading everything into memory:

```kotlin
suspend fun compressFile(input: FileChannel, output: FileChannel) {
    val chunkSize = 64 * 1024  // 64KB chunks

    SuspendingStreamingCompressor.create(CompressionAlgorithm.Gzip).use { compressor ->
        val buffer = PlatformBuffer.allocate(chunkSize)

        while (input.read(buffer) > 0) {
            buffer.resetForRead()
            compressor.compress(buffer).forEach { chunk ->
                output.write(chunk)
            }
            buffer.clear()
        }

        compressor.finish().forEach { chunk ->
            output.write(chunk)
        }
    }
}
```

## HTTP Response Decompression

Common pattern for handling gzipped HTTP responses:

```kotlin
suspend fun fetchAndDecompress(url: String): String {
    val response = httpClient.get(url) {
        header("Accept-Encoding", "gzip")
    }

    val contentEncoding = response.headers["Content-Encoding"]
    val body = response.body

    return if (contentEncoding == "gzip") {
        val output = mutableListOf<ReadBuffer>()
        SuspendingStreamingDecompressor.create(CompressionAlgorithm.Gzip).use { decompressor ->
            output += decompressor.decompress(body)
            output += decompressor.finish()
        }
        combineBuffers(output).readString()
    } else {
        body.readString()
    }
}
```

---

## Platform Optimizations

The examples above use the async API which works everywhere. On platforms with synchronous compression support, you can use more efficient APIs.

### Platform Support Matrix

| Platform | Sync API | Async API | Engine |
|----------|----------|-----------|--------|
| JVM | ✅ | ✅ | java.util.zip |
| Android | ✅ | ✅ | java.util.zip |
| iOS/macOS | ✅ | ✅ | zlib |
| JS (Node.js) | ✅ | ✅ | zlib module |
| JS (Browser) | ❌ | ✅ | CompressionStream |
| WasmJS | ❌ | ❌ | — |
| Linux Native | ❌ | ❌ | — |

### Check Platform Support at Runtime

```kotlin
if (supportsSyncCompression) {
    // Use faster sync API
} else {
    // Fall back to async API (browser JS)
}
```

### One-Shot API (Non-Browser Only)

For simple cases where you have all data upfront:

```kotlin
// Only available when supportsSyncCompression == true
val original = "Hello, World!".toReadBuffer()
val compressed = compress(original, CompressionAlgorithm.Gzip).getOrThrow()
val decompressed = decompress(compressed, CompressionAlgorithm.Gzip).getOrThrow()
```

Handle errors explicitly:

```kotlin
when (val result = compress(buffer, CompressionAlgorithm.Gzip)) {
    is CompressionResult.Success -> println("Compressed: ${result.buffer.remaining()} bytes")
    is CompressionResult.Failure -> println("Failed: ${result.message}")
}

// Or use convenience methods
val buffer = compress(data, algorithm).getOrThrow()  // throws on failure
val buffer = compress(data, algorithm).getOrNull()   // null on failure
```

### Synchronous Streaming API (Non-Browser Only)

The sync API avoids coroutine overhead and is slightly faster:

```kotlin
// Compression with callback
StreamingCompressor.create(CompressionAlgorithm.Gzip).use(
    onOutput = { compressedChunk ->
        socket.write(compressedChunk)
    }
) { compress ->
    compress("First chunk".toReadBuffer())
    compress("Second chunk".toReadBuffer())
    compress("Third chunk".toReadBuffer())
}

// Decompression with callback
StreamingDecompressor.create(CompressionAlgorithm.Gzip).use(
    onOutput = { decompressedChunk ->
        processData(decompressedChunk)
    }
) { decompress ->
    decompress(compressedChunk1)
    decompress(compressedChunk2)
}
```

### Sync Compression with Suspending I/O

When reading from a network socket (suspending) but using sync compression:

```kotlin
StreamingCompressor.create(CompressionAlgorithm.Gzip).useSuspending(
    onOutput = { chunk -> channel.send(chunk) }
) { compress ->
    while (socket.hasData()) {
        val data = socket.read()  // suspend call is OK here
        compress(data)            // sync compression
    }
}
```

### Choosing the Right API

```kotlin
suspend fun compressEfficiently(data: ReadBuffer): List<ReadBuffer> {
    return if (supportsSyncCompression) {
        // Platform supports sync - use one-shot for simplicity
        listOf(compress(data, CompressionAlgorithm.Gzip).getOrThrow())
    } else {
        // Browser - must use async
        val output = mutableListOf<ReadBuffer>()
        SuspendingStreamingCompressor.create(CompressionAlgorithm.Gzip).use { compressor ->
            output += compressor.compress(data)
            output += compressor.finish()
        }
        output
    }
}
```

---

## StreamProcessor Integration

For protocol parsing scenarios where you receive compressed data and need to parse structured content:

```kotlin
withPool { pool ->
    val processor = StreamProcessor.builder(pool)
        .decompress(CompressionAlgorithm.Gzip)
        .build()

    try {
        // Append compressed chunks as they arrive
        processor.append(compressedChunk1)
        processor.append(compressedChunk2)
        processor.finish()  // Signal no more input

        // Parse the decompressed data
        val messageType = processor.peekByte()
        val length = processor.peekInt(offset = 1)
        processor.skip(5)  // Skip header
        val payload = processor.readBuffer(length)
    } finally {
        processor.release()
    }
}
```

### With Network I/O

```kotlin
withPool { pool ->
    val processor = StreamProcessor.builder(pool)
        .decompress(CompressionAlgorithm.Gzip)
        .build()

    try {
        // Read from socket and decompress on the fly
        while (socket.hasData()) {
            processor.append(socket.read())  // socket.read() suspends
        }
        processor.finish()

        // Parse multiple messages from the stream
        while (processor.available() >= 4) {
            val length = processor.peekInt()
            if (processor.available() < 4 + length) break

            processor.skip(4)
            val message = processor.readBuffer(length)
            handleMessage(message)
        }
    } finally {
        processor.release()
    }
}
```

### Browser JavaScript (Async-Only)

For browser JS, use `buildSuspending()`:

```kotlin
val processor = StreamProcessor.builder(pool)
    .decompress(CompressionAlgorithm.Gzip)
    .buildSuspending()  // Returns SuspendingStreamProcessor

processor.append(chunk)  // suspend function
processor.finish()
val data = processor.readBuffer(processor.available())
processor.release()
```

---

## Memory Management

### Using BufferAllocator

Control how output buffers are allocated:

```kotlin
// Use direct memory (default) - best for I/O
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    allocator = BufferAllocator.Direct
)

// Use heap memory - faster allocation
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    allocator = BufferAllocator.Heap
)

// Use a specific zone
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    allocator = BufferAllocator.FromZone(AllocationZone.SharedMemory)
)
```

### Output Buffer Size

Tune the output chunk size for your use case:

```kotlin
// Smaller chunks - lower latency, more overhead
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    outputBufferSize = 4096
)

// Larger chunks - higher throughput, more memory
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    outputBufferSize = 65536
)
```

---

## Best Practices

1. **Start with the async API** - Works everywhere, including browser JS
2. **Use `use` extensions** - Automatically calls `finish()` and `close()`
3. **Use Gzip for HTTP** - Standard compression for web
4. **Check `supportsSyncCompression`** - Before using sync APIs
5. **Process in chunks** - For large files, stream data through
6. **Use StreamProcessor for protocols** - Combines decompression with parsing

---

## Unsupported Platforms

**WasmJS** and **Linux Native** don't currently support compression because:
- These platforms don't include zlib by default
- WasmJS would require bundling a WASM-compiled zlib
- Linux Native would require linking against the system's zlib library

Contributions to add support are welcome!
