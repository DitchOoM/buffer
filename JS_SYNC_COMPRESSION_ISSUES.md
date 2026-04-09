## JS Node sync StreamingCompressor: persistent stream fix applied

### Current status

- Context takeover: **FIXED** (358/408 Autobahn cases pass, up from 249)
- Custom windowBits compressor: **FIXED** — passed through factory to `createDeflateRaw({windowBits})`
- Custom windowBits decompressor: **NOT the issue** — see investigation below

Autobahn case 13.3.1 (custom windowBits + context takeover):
`Echo'ed message length differs from what I sent (got length 32, expected length 16)`

### windowBits investigation (ruled out)

The decompressor factory does not accept `windowBits` — it always uses the default (15).
This was investigated and confirmed **not incorrect**:

- Node.js `createInflateRaw()` auto-detects the compressed stream's window size
- C zlib's inflate with a larger window (15) correctly decompresses data from a smaller window (8-14)
- Tested: `createDeflateRaw({windowBits: 10})` + `createInflateRaw({})` (default 15) produces correct output
- Node.js rejects negative windowBits for `createDeflateRaw`/`createInflateRaw` (valid range: 8-15)

Adding `windowBits` to `StreamingDecompressor.create()` would be a **memory optimization**
(use smaller window to save RAM) but is not a correctness fix.

**Proof:** `SyncPersistentStreamTests.customWindowBitsCompressorWithDefaultDecompressor` compresses
with `windowBits=10` and decompresses with default (15) — 5 messages including 2KB+ payloads that
exceed the 1KB window, all round-trip correctly with context takeover. See:
`buffer-compression/src/commonTest/kotlin/com/ditchoom/buffer/compression/SyncPersistentStreamTests.kt`

The "got length 32, expected length 16" error is in the **WebSocket echo layer**, not the compression library.

---

## Original issue (RESOLVED): JS Node sync StreamingCompressor uses one-shot zlib calls — no context takeover, no custom windowBits

**File:** `buffer-compression/src/jsAndWasmJsMain/kotlin/com/ditchoom/buffer/compression/JsWasmStreamingCompression.kt`

### Problem

`JsNodeStreamingCompressor` (line 68) and `JsNodeStreamingDecompressor` (line 125) accumulate chunks and then call one-shot sync zlib functions per flush:

```kotlin
override fun flush(onOutput: (ReadBuffer) -> Unit) {
    val combined = combineJsByteArrays(accumulatedChunks, totalBytes)
    val result = nodeZlibSyncFlush(combined, algorithm, level)  // one-shot — fresh zlib stream
    onOutput(result.toPlatformBuffer(allocator))
    accumulatedChunks.clear()
}
```

Each `flush()` creates a fresh zlib stream internally. This means:
1. **No context takeover** — the LZ77 sliding window is discarded between messages
2. **No custom windowBits** — the `windowBits` parameter is received by the factory (line 17) but never passed to `JsNodeStreamingCompressor` or to `nodeZlibSyncFlush`
3. **`reset()` is a no-op** — just clears chunks, no zlib stream to reset

### Contrast with async path

`NodeTransformStreamingCompressor` (line 262) correctly uses persistent streams:

```kotlin
private fun initStream() {
    stream = createCompressStream(algorithm, level)  // createDeflateRaw() — persistent
}

override suspend fun flush(): List<ReadBuffer> {
    val s = stream ?: return emptyList()
    val output = s.writeAndFlush(chunks)  // writes to persistent stream
    return output.map { it.toPlatformBuffer(allocator) }
}

override fun reset() {
    stream?.destroy()
    initStream()  // new stream — correct reset
}
```

This path maintains zlib state across calls (context takeover works) but is `suspend` — can't be used by the websocket's sync compression path.

### Impact

WebSocket permessage-deflate with context takeover fails on JS Node because `DefaultWebSocketClient` uses the sync `StreamingCompressor`, which creates fresh zlib streams per message.

Autobahn results:
- Categories 13.3/13.4 (context takeover + custom windowBits): all fail on JS
- Category 12 compression with high message counts: some fail due to accumulated window desync

### Fix

`JsNodeStreamingCompressor` should use a persistent `createDeflateRaw()` stream (same as the async path) but call it synchronously via Node.js `zlib.deflateRawSync()` with the stream, or use the `_processChunk` internal sync API.

Simplest approach — use the Node.js Transform stream synchronously:

```kotlin
private class JsNodeStreamingCompressor(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    windowBits: Int,
    override val bufferFactory: BufferFactory,
) : StreamingCompressor {
    private var stream = createDeflateRawStream(algorithm, level, windowBits)

    override fun compress(input: ReadBuffer, onOutput: (ReadBuffer) -> Unit) {
        // Write to persistent stream
    }

    override fun flush(onOutput: (ReadBuffer) -> Unit) {
        // Flush persistent stream (Z_SYNC_FLUSH) — state preserved
    }

    override fun reset() {
        stream.destroy()
        stream = createDeflateRawStream(algorithm, level, windowBits)
    }
}
```

Where `createDeflateRawStream` passes `windowBits`:

```kotlin
fun createDeflateRawStream(algorithm, level, windowBits): NodeTransformHandle {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    if (windowBits != 0) {
        options["windowBits"] = windowBits  // ← currently missing
    }
    return NodeTransformHandle(zlib.createDeflateRaw(options))
}
```

Same fix needed for `JsNodeStreamingDecompressor` — use persistent `createInflateRaw()` instead of one-shot `nodeZlibDecompressSync`.

### Also fix

The factory at line 12-26 receives `windowBits` but discards it:

```kotlin
actual fun StreamingCompressor.Companion.create(
    algorithm, level, allocator, outputBufferSize,
    windowBits,  // ← received but never passed to JsNodeStreamingCompressor
): StreamingCompressor =
    if (isNodeJs) {
        JsNodeStreamingCompressor(algorithm, level, allocator)  // ← windowBits dropped
    }
```

Pass `windowBits` through to the constructor.
