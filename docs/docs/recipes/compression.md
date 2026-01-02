---
sidebar_position: 4
title: Compression
---

# Compression

The `buffer-compression` module provides cross-platform compression and decompression.

## Installation

Add the compression module to your dependencies:

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-compression:<latest-version>")
}
```

## One-Shot Compression

:::caution Browser JavaScript Not Supported
The one-shot `compress()` and `decompress()` functions are synchronous and **not available in browser JavaScript**. Use `SuspendingStreamingCompressor` for browser support.
:::

Compress and decompress entire buffers:

```kotlin
val original = "Hello, World!".toReadBuffer()
val compressed = compress(original, CompressionAlgorithm.Gzip).getOrThrow()

val decompressed = decompress(compressed, CompressionAlgorithm.Gzip).getOrThrow()
val text = decompressed.readString(decompressed.remaining())
```

## Compression Algorithms

```kotlin
// Gzip - most common for HTTP
compress(buffer, CompressionAlgorithm.Gzip)

// Deflate - zlib format with header/trailer
compress(buffer, CompressionAlgorithm.Deflate)

// Raw - no headers, for custom protocols
compress(buffer, CompressionAlgorithm.Raw)
```

## Compression Levels

```kotlin
compress(buffer, algorithm, CompressionLevel.BestSpeed)       // Fastest
compress(buffer, algorithm, CompressionLevel.BestCompression) // Smallest
compress(buffer, algorithm, CompressionLevel.Default)         // Balanced
compress(buffer, algorithm, CompressionLevel.NoCompression)   // Store only
compress(buffer, algorithm, CompressionLevel.Custom(4))       // Custom 0-9
```

## Error Handling

```kotlin
when (val result = compress(buffer, CompressionAlgorithm.Gzip)) {
    is CompressionResult.Success -> println("Compressed: ${result.buffer.remaining()} bytes")
    is CompressionResult.Failure -> println("Failed: ${result.message}")
}

// Or use convenience methods
val buffer = compress(data, algorithm).getOrThrow()  // throws on failure
val buffer = compress(data, algorithm).getOrNull()   // null on failure
```

## Streaming Compression

For large data or network streams, use the suspending API which works on **all platforms** including browser JavaScript:

```kotlin
SuspendingStreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use { compress ->
    compress("First chunk".toReadBuffer())
    compress("Second chunk".toReadBuffer())
    compress("Third chunk".toReadBuffer())
    // finish() and close() called automatically
}
```

### Streaming Decompression

```kotlin
SuspendingStreamingDecompressor.create(algorithm = CompressionAlgorithm.Gzip).use { decompress ->
    decompress(compressedChunk1)
    decompress(compressedChunk2)
    // finish() and close() called automatically
}
```

### Collecting Output

The suspending API returns output chunks from each call:

```kotlin
SuspendingStreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use { compress ->
    val chunks1 = compress(data1)  // returns List<ReadBuffer>
    val chunks2 = compress(data2)

    // Send chunks as they're produced
    chunks1.forEach { sendToNetwork(it) }
    chunks2.forEach { sendToNetwork(it) }
}
```

## Synchronous API

:::caution Browser JavaScript Not Supported
The synchronous API is **not available in browser JavaScript**. Browser JS only supports the async `CompressionStream` API. Use `SuspendingStreamingCompressor` instead, or check `supportsSyncCompression` at runtime.
:::

For platforms with synchronous compression support (JVM, Android, iOS/macOS, Node.js), use the callback-based API:

```kotlin
StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
    onOutput = { compressedChunk ->
        socket.write(compressedChunk)
    }
) { compress ->
    compress("First chunk".toReadBuffer())
    compress("Second chunk".toReadBuffer())
    compress("Third chunk".toReadBuffer())
}
```

### Synchronous Decompression

```kotlin
StreamingDecompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
    onOutput = { decompressedChunk ->
        processData(decompressedChunk)
    }
) { decompress ->
    decompress(compressedChunk1)
    decompress(compressedChunk2)
}
```

### With Suspending I/O

When using synchronous compression with suspending I/O (e.g., network sockets), use `useSuspending`:

```kotlin
StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).useSuspending(
    onOutput = { chunk -> channel.send(chunk) }
) { compress ->
    while (socket.hasData()) {
        val chunk = socket.read()  // suspend OK
        compress(chunk)
    }
}
```

## Platform Support

| Platform | Engine | Sync API | Async API |
|----------|--------|----------|-----------|
| JVM | java.util.zip | ✅ | ✅ |
| Android | java.util.zip | ✅ | ✅ |
| iOS/macOS | zlib | ✅ | ✅ |
| JS (Node.js) | zlib module | ✅ | ✅ |
| JS (Browser) | CompressionStream | ❌ | ✅ |
| WasmJS | — | ❌ | ❌ |
| Linux Native | — | ❌ | ❌ |

### Unsupported Platforms

**WasmJS** and **Linux Native** are not currently supported because:
- These platforms don't include zlib by default
- WasmJS would require bundling a WASM-compiled zlib or using browser APIs (which aren't available in non-browser WASM environments)
- Linux Native would require linking against the system's zlib library

Contributions to add support for these platforms are welcome!

Check platform support at runtime:

```kotlin
if (supportsSyncCompression) {
    compress(buffer, CompressionAlgorithm.Gzip)
} else {
    // Browser - use SuspendingStreamingCompressor
}
```

## StreamProcessor Integration

For protocol parsing scenarios where you receive compressed data and need to parse structured content, use the `StreamProcessor` builder API:

```kotlin
withPool { pool ->
    val processor = StreamProcessor.builder(pool)
        .decompress(CompressionAlgorithm.Gzip)
        .build()

    try {
        // Append compressed chunks as they arrive
        processor.append(compressedChunk1)
        processor.append(compressedChunk2)
        processor.finish()  // Signal no more data

        // Now parse the decompressed data
        val header = processor.peekInt()
        val payload = processor.readBuffer(processor.available())
    } finally {
        processor.release()
    }
}
```

### With Suspending I/O

The sync `StreamProcessor` works in coroutine contexts - the suspend happens before `append()`:

```kotlin
withPool { pool ->
    val processor = StreamProcessor.builder(pool)
        .decompress(CompressionAlgorithm.Gzip)
        .build()

    try {
        // Suspend on socket.read(), then append synchronously
        while (socket.hasData()) {
            processor.append(socket.read())  // socket.read() is suspend
        }
        processor.finish()

        val payload = processor.readBuffer(processor.available())
    } finally {
        processor.release()
    }
}
```

For browser JavaScript (async-only decompression), use `buildSuspending()`:

```kotlin
val processor = StreamProcessor.builder(pool)
    .decompress(CompressionAlgorithm.Gzip)
    .buildSuspending()  // Returns SuspendingStreamProcessor

processor.append(chunk)  // suspend function
processor.finish()
val data = processor.readBuffer(processor.available())
processor.release()
```

This approach:
- Automatically decompresses data as chunks are appended
- Provides efficient peek/read operations on decompressed data
- Works with the buffer pool for zero-copy slicing where possible

## Best Practices

1. **Use `use` extensions** - Auto-calls `finish()` and `close()`
2. **Use Gzip for HTTP** - Standard for web compression
3. **Use `useSuspending` for network I/O** - Efficient sync compression with suspend I/O
4. **Check `supportsSyncCompression`** - Browser JS requires async API
5. **Use StreamProcessor for protocol parsing** - Combines decompression with structured data parsing
