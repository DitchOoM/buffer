---
sidebar_position: 3
title: Stream Processing
---

# Stream Processing

Handle data that arrives in chunks, like network packets or file reads.

## The Problem

Network data doesn't arrive in neat message boundaries:

![Stream Fragmentation Problem](/img/stream-fragmentation.svg)

Buffer's `StreamProcessor` handles this transparently.

## BufferStream

Split a buffer into chunks for processing:

```kotlin
import com.ditchoom.buffer.stream.BufferStream

val largeBuffer = PlatformBuffer.allocate(1024 * 1024)  // 1MB
// ... fill buffer ...
largeBuffer.resetForRead()

val stream = BufferStream(largeBuffer, chunkSize = 8192)

stream.forEachChunk { chunk ->
    // Process each 8KB chunk
    processChunk(chunk.buffer)

    if (chunk.isLast) {
        println("Processing complete at offset ${chunk.offset}")
    }
}
```

## StreamProcessor

Parse protocols that span chunk boundaries:

```kotlin
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.buffer.pool.BufferPool

val pool = BufferPool(defaultBufferSize = 8192)

// Using the builder pattern
val processor = StreamProcessor.builder(pool).build()

// Or directly
val processor2 = StreamProcessor.create(pool)

// Simulate network data arriving
processor.append(networkPacket1)
processor.append(networkPacket2)

// Parse messages with length-prefix protocol
while (processor.available() >= 4) {
    val length = processor.peekInt()  // Peek without consuming

    if (processor.available() >= 4 + length) {
        processor.skip(4)  // Skip length header
        val payload = processor.readBuffer(length)
        handleMessage(payload)
    } else {
        break  // Wait for more data
    }
}

processor.release()
```

## StreamProcessor Operations

### Peek Operations (don't consume data)

All peek operations accept an optional `offset` parameter to look ahead without consuming:

```kotlin
// Peek at bytes without consuming
val firstByte = processor.peekByte()
val byteAt5 = processor.peekByte(offset = 5)

// Peek multi-byte values (all support offset)
val shortValue = processor.peekShort()
val intValue = processor.peekInt()
val longValue = processor.peekLong()

// Peek at offset - useful for parsing headers
val messageType = processor.peekByte()           // byte 0: message type
val messageLength = processor.peekInt(offset = 1) // bytes 1-4: length
val timestamp = processor.peekLong(offset = 5)    // bytes 5-12: timestamp
```

### Pattern Matching

```kotlin
// Check for magic bytes (e.g., gzip header 0x1f 0x8b)
val gzipMagic = PlatformBuffer.wrap(byteArrayOf(0x1f, 0x8b.toByte()))
if (processor.peekMatches(gzipMagic)) {
    // It's gzip compressed
}

// Find first mismatch index (-1 if matches)
val mismatchIndex = processor.peekMismatch(expectedPattern)
```

### Read Operations (consume data)

```kotlin
val byte = processor.readByte()
val short = processor.readShort()
val int = processor.readInt()
val long = processor.readLong()

// Read into a buffer (zero-copy when possible)
val payload = processor.readBuffer(100)
```

### Skip

```kotlin
// Skip bytes efficiently
processor.skip(4)  // Skip header
```

## Processing Flow

![Stream Processing Flow](/img/stream-processing-flow.svg)

## Zero-Copy vs Copy

StreamProcessor optimizes for zero-copy:

![Zero-Copy vs Copy](/img/zero-copy-vs-copy.svg)

## Example: HTTP Chunked Transfer

```kotlin
class HttpChunkedParser(private val pool: BufferPool) {
    private val processor = StreamProcessor.create(pool)
    private val CRLF = "\r\n".toReadBuffer()  // No intermediate ByteArray

    fun append(data: ReadBuffer) {
        processor.append(data)
    }

    fun parseChunks(): List<ReadBuffer> {
        val chunks = mutableListOf<ReadBuffer>()

        while (true) {
            // Find chunk size line
            val sizeLine = readLine() ?: break
            val chunkSize = sizeLine.trim().toIntOrNull(16) ?: break

            if (chunkSize == 0) break  // End of chunks

            if (processor.available() < chunkSize + 2) break  // Need more data

            val chunk = processor.readBuffer(chunkSize)
            chunks.add(chunk)

            processor.skip(2)  // Skip trailing CRLF
        }

        return chunks
    }

    private fun readLine(): String? {
        // Scan for CRLF
        for (i in 0 until processor.available() - 1) {
            if (processor.peekByte(i) == '\r'.code.toByte() &&
                processor.peekByte(i + 1) == '\n'.code.toByte()) {

                val line = processor.readBuffer(i)
                processor.skip(2)  // Skip CRLF
                return line.readString(i)
            }
        }
        return null
    }

    fun release() {
        processor.release()
    }
}
```

## Builder Pattern with Transforms

The `StreamProcessor.builder()` API allows composing transforms like decompression:

```kotlin
// With compression module
val processor = StreamProcessor.builder(pool)
    .decompress(CompressionAlgorithm.Gzip)  // From buffer-compression
    .build()

// Append compressed data
processor.append(compressedChunk)
processor.finish()  // Signal no more input

// Read decompressed data
val data = processor.readBuffer(processor.available())
processor.release()
```

See [Compression](/recipes/compression#streamprocessor-integration) for full details.

## StreamingStringDecoder

Decode UTF-8 bytes to strings with automatic handling of multi-byte sequences split across chunk boundaries:

```kotlin
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.StreamingStringDecoderConfig

val decoder = StreamingStringDecoder()
val result = StringBuilder()

// Process chunks as they arrive (handles split UTF-8 sequences)
chunks.forEach { chunk ->
    decoder.decode(chunk, result)
}

// Finalize and get result
decoder.finish(result)
val text = result.toString()

// Reuse for next stream
decoder.reset()
```

### Key Features

- **Zero intermediate allocations**: Decodes directly to `Appendable`
- **Automatic boundary handling**: Multi-byte UTF-8 sequences split across chunks are handled transparently
- **Reusable**: Create once, use for multiple streams via `reset()`
- **Platform-optimized**: Uses native APIs (JVM CharsetDecoder, simdutf on Linux, TextDecoder on JS)

### Error Handling

Configure how malformed input is handled:

```kotlin
import com.ditchoom.buffer.DecoderErrorAction

// Strict mode (default) - throws on malformed input
val strict = StreamingStringDecoder(
    StreamingStringDecoderConfig(onMalformedInput = DecoderErrorAction.REPORT)
)

// Lenient mode - replaces errors with U+FFFD
val lenient = StreamingStringDecoder(StreamingStringDecoderConfig.LENIENT)
```

### Use with Compression

Combine with `StreamProcessor` for decompressing and decoding in one pipeline:

```kotlin
val pool = BufferPool(defaultBufferSize = 8192)
val processor = StreamProcessor.builder(pool)
    .decompress(CompressionAlgorithm.Gzip)
    .build()

val decoder = StreamingStringDecoder()
val result = StringBuilder()

// Append compressed data
processor.append(compressedChunk)
processor.finish()

// Decode decompressed output
while (processor.available() > 0) {
    val chunk = processor.readBuffer(minOf(processor.available(), 8192))
    decoder.decode(chunk, result)
}
decoder.finish(result)

processor.release()
println(result.toString())
```

## Best Practices

1. **Use peek before read** - check data availability first
2. **Use pools** - StreamProcessor works best with pooled buffers
3. **Call release()** - clean up when done
4. **Handle fragmentation** - always check `available()` before reading
5. **Prefer peekMatches** - for magic byte detection
6. **Call finish() for transforms** - signals end of input for decompression etc.
7. **Use StreamingStringDecoder** - for efficient chunked UTF-8 decoding without intermediate String allocations
