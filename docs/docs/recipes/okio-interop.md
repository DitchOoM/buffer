---
sidebar_position: 11
title: Okio Interop
---

# Okio Interop

The `buffer-okio` module bridges `PlatformBuffer` to [Okio](https://square.github.io/okio/)'s
`Source` / `Sink`, `Buffer`, and `ByteString` types. Use it to feed a buffer into an Okio pipeline
(file I/O, hashing, `BufferedSource` parsing) or to pull Okio data into a `PlatformBuffer`.

## Installation

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-okio:<latest-version>")
}
```

The module depends on Okio (3.17.0) and re-exports it, so you don't need a separate Okio dependency.
It ships for the full set of `buffer` targets.

## Copy semantics

| Function | Direction | Semantics |
|----------|-----------|-----------|
| `WriteBuffer.asOkioSink()` | buffer → okio | streaming view; writes land in the buffer |
| `ReadBuffer.asOkioSource()` | okio → buffer | streaming view; reads drain the buffer |
| `Source.readInto(dst, byteCount)` | okio → buffer | copies up to `byteCount` bytes |
| `Source.transferTo(dst)` | okio → buffer | copies until source EOF |
| `ReadBuffer.copyToOkioBuffer()` | buffer → okio | non-destructive copy (position unchanged) |
| `ReadBuffer.copyToByteString()` | buffer → okio | immutable snapshot (always copies) |
| `Buffer.copyToPlatformBuffer(factory, byteOrder)` | okio → buffer | non-destructive copy (source not consumed) |
| `ByteString.copyToPlatformBuffer(factory, byteOrder)` | okio → buffer | copy (ByteString is immutable) |

The `as*` views stream through the underlying buffer with **one copy per read/write** and never alias
our backing array into an Okio segment. The `copyTo*` helpers take an independent snapshot.

## Buffer → Okio

### Stream a buffer into a `Sink`

`asOkioSink()` returns an Okio `Sink` view over a `WriteBuffer`. Bytes written to the sink land in the
buffer. Wrap it with `.buffer()` for the ergonomic `BufferedSink` API.

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.okio.asOkioSink
import okio.buffer

val buffer = BufferFactory.Default.allocate(1024)
val sink = buffer.asOkioSink().buffer()
sink.writeUtf8("Hello, Okio!")
sink.flush()

buffer.resetForRead()
val text = buffer.readString(buffer.remaining())
```

### Snapshot a buffer as an Okio `Buffer` or `ByteString`

`copyToOkioBuffer()` copies the remaining bytes into a fresh Okio `Buffer` **without moving the source
position**. `copyToByteString()` produces an immutable `ByteString` snapshot — ideal as a map key, for
hashing, or for equality checks.

```kotlin
import com.ditchoom.buffer.okio.copyToOkioBuffer
import com.ditchoom.buffer.okio.copyToByteString

val payload: ReadBuffer = /* ... */
val okioBuffer = payload.copyToOkioBuffer()   // payload.position() unchanged
val digest = payload.copyToByteString().sha256()
```

## Okio → Buffer

### Read a `Source` into a buffer

`readInto` copies up to `byteCount` bytes (defaulting to the destination's remaining capacity) and
returns how many were actually transferred, stopping at destination-full or source-EOF.

```kotlin
import com.ditchoom.buffer.okio.readInto

val dst = BufferFactory.Default.allocate(4096)
val copied: Long = source.readInto(dst)      // fills dst, or stops at source EOF
dst.resetForRead()
```

### Drain a whole source

`transferTo` reads until the source is exhausted. If the source outlasts the destination's capacity,
the write fails fast with the underlying overflow exception.

```kotlin
import com.ditchoom.buffer.okio.transferTo

val dst = BufferFactory.Default.allocate(estimatedSize)
val total: Long = source.transferTo(dst)     // source-EOF driven
dst.resetForRead()
```

### Convert an Okio `Buffer` or `ByteString` into a `PlatformBuffer`

`copyToPlatformBuffer` on an Okio `Buffer` copies through a **peek**, so the original is not consumed.
On a `ByteString` it always copies (immutable, no backing-array access). Both leave the result
positioned for reading.

```kotlin
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.okio.copyToPlatformBuffer

val fromBuffer = okioBuffer.copyToPlatformBuffer()               // Buffer not consumed
val fromBytes = byteString.copyToPlatformBuffer(
    factory = BufferFactory.Default,
    byteOrder = ByteOrder.LITTLE_ENDIAN,
)
```

## Example: hash a buffer with Okio

```kotlin
import com.ditchoom.buffer.okio.asOkioSource
import okio.HashingSink
import okio.blackholeSink
import okio.buffer

val payload: ReadBuffer = /* ... */
val hashing = HashingSink.sha256(blackholeSink())
payload.asOkioSource().buffer().use { it.readAll(hashing) }
val sha256 = hashing.hash        // payload streamed through Okio, one copy
```

## Lifetime rules

The `asOkioSource()` / `asOkioSink()` views are thin adapters over the live buffer — they don't own it.

- **Don't let a view outlive its buffer.** A view over a released [pooled buffer](./buffer-pooling)
  fails fast rather than reading another acquirer's bytes.
- **Views are single-buffer.** A `Sink` view fills exactly one buffer; for a growing stream, drain to
  a `copyTo*` snapshot or a larger allocation.

Using a view after its buffer is gone throws `IllegalStateException` (`"asOkioSource() view has been
closed"` / `"asOkioSink() view has been closed"`).

## See also

- [kotlinx-io Interop](./kotlinx-io-interop) — the same bridge shape for kotlinx-io `RawSource` / `RawSink`
- [Platform Interop](./platform-interop) — native `toNativeData()` handles for zero-copy platform APIs
