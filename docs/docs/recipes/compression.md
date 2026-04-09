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

### Compress and Decompress

```kotlin
import com.ditchoom.buffer.compression.*

// Compress
val data = "Hello, World!".toReadBuffer()
val compressed = compressAsync(data, CompressionAlgorithm.Gzip)

// Decompress
val decompressed = decompressAsync(compressed, CompressionAlgorithm.Gzip)
val text = decompressed.readString(decompressed.remaining())
```

### Round-Trip Example

```kotlin
suspend fun roundTrip(input: String): String {
    val compressed = compressAsync(input.toReadBuffer())
    val decompressed = decompressAsync(compressed)
    return decompressed.readString(decompressed.remaining())
}
```

## Streaming API

For large data or network scenarios where data arrives in chunks. The streaming API has two variants:

- **Scoped** (`compressScoped`, `decompressScoped`, `finishScoped`, `flushScoped`) — auto-releases output buffers when the lambda returns. **Preferred for most use cases.**
- **Unsafe** (`compressUnsafe`, `decompressUnsafe`, `finishUnsafe`, `flushUnsafe`) — caller owns the output buffers and must manage their lifecycle.

### Compress and Send Over Network (Scoped)

```kotlin
val compressor = StreamingCompressor.create(CompressionAlgorithm.Gzip)
try {
    compressor.compressScoped(data.toReadBuffer()) { socket.write(this) }
    compressor.finishScoped { socket.write(this) }
} finally {
    compressor.close()
}
```

Or use the `use` extension which calls `finish()` and `close()` automatically:

```kotlin
StreamingCompressor.create(CompressionAlgorithm.Gzip).use(
    onOutput = { socket.write(it) }
) { compress ->
    compress(data.toReadBuffer())
}
```

### Flush vs Finish

The streaming compressor provides two ways to emit output:

- **`flushScoped`/`flushUnsafe`** — Produces output that can be immediately decompressed, but keeps the stream open for more data. The output ends with a sync marker (`00 00 FF FF`).
- **`finishScoped`/`finishUnsafe`** — Finalizes the stream and produces the final output. The stream cannot accept more data after this.

Use flush when you need independently decompressible messages within a single compression context:

```kotlin
val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)
try {
    // First message - can be decompressed immediately
    compressor.compressScoped("message1".toReadBuffer()) {}
    compressor.flushScoped { socket.write(this) }

    // Second message - still using same compression context
    compressor.compressScoped("message2".toReadBuffer()) {}
    compressor.flushScoped { socket.write(this) }

    // When done, call finish
    compressor.finishScoped { socket.write(this) }
} finally {
    compressor.close()
}
```

:::note
`flushScoped`/`flushUnsafe` is not supported in browser JavaScript. Use `finishScoped` instead, or check `supportsSyncCompression` before using flush.
:::

### Receive and Decompress from Network (Scoped)

```kotlin
val output = BufferFactory.managed().allocate(expectedSize)
val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Gzip)
try {
    while (socket.hasData()) {
        decompressor.decompressScoped(socket.read()) { output.write(this) }
    }
    decompressor.finishScoped { output.write(this) }
} finally {
    decompressor.close()
}
output.resetForRead()
```

### Receive and Decompress (Unsafe — Caller Manages Buffers)

When you need to collect output buffers for later processing:

```kotlin
val output = mutableListOf<ReadBuffer>()
val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Gzip)
try {
    while (socket.hasData()) {
        decompressor.decompressUnsafe(socket.read()) { output += it }
    }
    decompressor.finishUnsafe { output += it }
} finally {
    decompressor.close()
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

## Sync Flush Marker Handling

Some protocols (like WebSocket permessage-deflate) require stripping or appending the deflate sync marker (`00 00 FF FF`). The buffer module provides utilities for this:

### Convenience Functions

For most use cases, use the high-level functions:

```kotlin
// Compress with sync flush and strip the marker
val compressed = compressWithSyncFlush(data.toReadBuffer())

// Decompress data that had the marker stripped
val decompressed = decompressWithSyncFlush(compressed)
```

### Low-Level Utilities

For more control, use the extension functions directly:

```kotlin
// Strip the sync marker from the end of compressed data
val stripped = compressedBuffer.stripSyncFlushMarker()
```

### Manual Marker Handling

When decompressing data that had the sync marker stripped, feed the marker as a separate buffer to avoid copying the compressed data:

```kotlin
val output = BufferFactory.managed().allocate(expectedSize)
val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
try {
    decompressor.decompressScoped(compressedData) { output.write(this) }

    // Feed the sync marker separately (no copy of compressed data)
    val marker = BufferFactory.Default.allocate(4)
    marker.writeInt(DeflateFormat.SYNC_FLUSH_MARKER)
    marker.resetForRead()
    decompressor.decompressScoped(marker) { output.write(this) }
    decompressor.finishScoped { output.write(this) }
} finally {
    decompressor.close()
}
output.resetForRead()
```

### The Sync Marker Constant

The marker value is available as a constant:

```kotlin
DeflateFormat.SYNC_FLUSH_MARKER  // 0x0000FFFF (00 00 FF FF)
```

:::note
These utilities work with raw deflate (`CompressionAlgorithm.Raw`). The sync marker is a deflate format concept, not specific to any protocol.
:::

## Compression Levels

Control the speed/size tradeoff:

```kotlin
// Fastest compression
compressAsync(data, level = CompressionLevel.BestSpeed)

// Smallest output
compressAsync(data, level = CompressionLevel.BestCompression)

// Balanced (default)
compressAsync(data, level = CompressionLevel.Default)

// Custom level 0-9
compressAsync(data, level = CompressionLevel.Custom(4))
```

## Handling Large Files

For large files, process in chunks to avoid loading everything into memory:

```kotlin
fun compressFile(input: FileChannel, output: FileChannel) {
    val chunkSize = 64 * 1024  // 64KB chunks

    StreamingCompressor.create(CompressionAlgorithm.Gzip).use(
        onOutput = { output.write(it) }
    ) { compress ->
        val buffer = BufferFactory.Default.allocate(chunkSize)
        while (input.read(buffer) > 0) {
            buffer.resetForRead()
            compress(buffer)
            buffer.clear()
        }
    }
}
```

Or with the scoped API for more control:

```kotlin
fun compressFile(input: FileChannel, output: FileChannel) {
    val chunkSize = 64 * 1024
    val compressor = StreamingCompressor.create(CompressionAlgorithm.Gzip)
    try {
        val buffer = BufferFactory.Default.allocate(chunkSize)
        while (input.read(buffer) > 0) {
            buffer.resetForRead()
            compressor.compressScoped(buffer) { output.write(this) }
            buffer.clear()
        }
        compressor.finishScoped { output.write(this) }
    } finally {
        compressor.close()
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
        val decompressed = decompressAsync(body, CompressionAlgorithm.Gzip)
        decompressed.readString(decompressed.remaining())
    } else {
        body.readString(body.remaining())
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
| Linux Native | ✅ | ✅ | zlib |
| JS (Node.js) | ✅ | ✅ | zlib module |
| JS (Browser) | ❌ | ✅ | CompressionStream |
| WasmJS | ❌ | ❌ | — |

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

The sync API avoids coroutine overhead and is slightly faster.

**Using `use` (auto finish + close):**

```kotlin
// Compression
StreamingCompressor.create(CompressionAlgorithm.Gzip).use(
    onOutput = { compressedChunk -> socket.write(compressedChunk) }
) { compress ->
    compress("First chunk".toReadBuffer())
    compress("Second chunk".toReadBuffer())
    compress("Third chunk".toReadBuffer())
}

// Decompression
StreamingDecompressor.create(CompressionAlgorithm.Gzip).use(
    onOutput = { decompressedChunk -> processData(decompressedChunk) }
) { decompress ->
    decompress(compressedChunk1)
    decompress(compressedChunk2)
}
```

**Using scoped API (auto-release output buffers per chunk):**

```kotlin
val compressor = StreamingCompressor.create(CompressionAlgorithm.Gzip)
try {
    compressor.compressScoped("First chunk".toReadBuffer()) { socket.write(this) }
    compressor.compressScoped("Second chunk".toReadBuffer()) { socket.write(this) }
    compressor.finishScoped { socket.write(this) }
} finally {
    compressor.close()
}
```

### Sync Compression with Suspending I/O

When reading from a network socket (suspending) but using sync compression, use `useSuspending`:

```kotlin
StreamingCompressor.create(CompressionAlgorithm.Gzip).useSuspending(
    onOutput = { chunk -> channel.send(chunk) }
) { compress ->
    while (socket.hasData()) {
        val data = socket.read()  // suspend call is OK here
        compress(data)            // sync compression, output auto-finished on exit
    }
}
```

### Choosing the Right API

```kotlin
suspend fun compressEfficiently(data: ReadBuffer): ReadBuffer {
    return if (supportsSyncCompression) {
        // Platform supports sync - use one-shot for simplicity
        compress(data, CompressionAlgorithm.Gzip).getOrThrow()
    } else {
        // Browser - must use async
        compressAsync(data, CompressionAlgorithm.Gzip)
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

### Using BufferFactory

Control how output buffers are allocated:

```kotlin
// Use direct memory (default) - best for I/O
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    bufferFactory = BufferFactory.Default
)

// Use heap memory - faster allocation
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    bufferFactory = BufferFactory.managed()
)

// Use pool-backed factory for buffer reuse
val pool = BufferPool(defaultBufferSize = 32768)
SuspendingStreamingCompressor.create(
    algorithm = CompressionAlgorithm.Gzip,
    bufferFactory = pool
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

1. **Prefer scoped APIs** (`compressScoped`, `decompressScoped`, `finishScoped`) — auto-releases output buffers, preventing leaks
2. **Use `use` extensions** — automatically calls `finish()` and `close()`
3. **Start with the async API** — works everywhere, including browser JS
4. **Use Gzip for HTTP** — standard compression for web
5. **Check `supportsSyncCompression`** — before using sync APIs
6. **Process in chunks** — for large files, stream data through
7. **Use StreamProcessor for protocols** — combines decompression with parsing

---

## Unsupported Platforms

**WasmJS** doesn't currently support compression because it would require bundling a WASM-compiled zlib library.

Contributions to add support are welcome!
