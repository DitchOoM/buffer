---
sidebar_position: 10
title: kotlinx-io Interop
---

# kotlinx-io Interop

The `buffer-kotlinx-io` module bridges `PlatformBuffer` to [kotlinx-io](https://github.com/Kotlin/kotlinx-io)'s
`RawSource` / `RawSink` and `Buffer` types. Use it to feed a buffer into any kotlinx-io pipeline, or to
pull kotlinx-io data into a `PlatformBuffer` for zero-copy platform interop and codec decoding.

## Installation

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-kotlinx-io:<latest-version>")
}
```

The module depends on `kotlinx-io-core` (0.9.1) and re-exports it, so you don't need a separate
kotlinx-io dependency. It ships for the full set of `buffer` targets.

## Copy semantics

Every bridge in this module has an explicit, documented contract:

| Function | Direction | Semantics |
|----------|-----------|-----------|
| `WriteBuffer.asRawSink()` | buffer → kotlinx-io | streaming view; writes land in the buffer |
| `ReadBuffer.asRawSource()` | kotlinx-io → buffer | streaming view; reads drain the buffer |
| `RawSource.readInto(dst, byteCount)` | kotlinx-io → buffer | copies up to `byteCount` bytes |
| `RawSource.transferTo(dst)` | kotlinx-io → buffer | copies until source EOF |
| `ReadBuffer.copyToKotlinxIoBuffer()` | buffer → kotlinx-io | non-destructive copy (position unchanged) |
| `Buffer.copyToPlatformBuffer(factory, byteOrder)` | kotlinx-io → buffer | non-destructive copy (source not consumed) |

The `as*` views stream through the underlying buffer with **one copy per read/write** and never alias
our backing array into a kotlinx-io segment. The `copyTo*` helpers take an independent snapshot — the
copy verb in the name signals the cost at the call site.

## Buffer → kotlinx-io

### Stream a buffer into a `RawSink`

`asRawSink()` returns a `RawSink` view over a `WriteBuffer`. Bytes written to the sink land in the buffer.

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.kotlinxio.asRawSink
import kotlinx.io.buffered
import kotlinx.io.writeString

val buffer = BufferFactory.Default.allocate(1024)
val sink = buffer.asRawSink().buffered()
sink.writeString("Hello, kotlinx-io!")
sink.flush()

buffer.resetForRead()
val text = buffer.readString(buffer.remaining())
```

### Snapshot a buffer as a kotlinx-io `Buffer`

`copyToKotlinxIoBuffer()` copies the remaining bytes into a fresh kotlinx-io `Buffer` **without moving
the source position** — the original stays readable.

```kotlin
import com.ditchoom.buffer.kotlinxio.copyToKotlinxIoBuffer

val payload: ReadBuffer = /* ... */
val kxBuffer = payload.copyToKotlinxIoBuffer()  // payload.position() unchanged
// hand kxBuffer to any kotlinx-io API; mutating payload afterwards does not affect it
```

## kotlinx-io → Buffer

### Read a `RawSource` into a buffer

`readInto` copies up to `byteCount` bytes (defaulting to the destination's remaining capacity) and
returns how many were actually transferred. It stops at whichever comes first: the destination filling
up, or the source reaching EOF.

```kotlin
import com.ditchoom.buffer.kotlinxio.readInto

val dst = BufferFactory.Default.allocate(4096)
val copied: Long = source.readInto(dst)      // fills dst, or stops at source EOF
dst.resetForRead()
```

:::note EOF convention
kotlinx-io's `RawSource.readAtMostTo` returns `-1` at EOF, but `readInto` folds EOF into its running
total and **never returns a negative value** — a source already exhausted on entry returns `0`. If the
last chunk both fills `dst` to `byteCount` and drains the source, `readInto` returns `byteCount`
directly; it never issues a further probe read that would observe EOF.
:::

### Drain a whole source

`transferTo` reads until the source is exhausted. There is no `byteCount` stop condition — if the
source outlasts the destination's capacity, the write fails fast with the underlying overflow
exception.

```kotlin
import com.ditchoom.buffer.kotlinxio.transferTo

val dst = BufferFactory.Default.allocate(estimatedSize)
val total: Long = source.transferTo(dst)     // source-EOF driven
dst.resetForRead()
```

### Convert a kotlinx-io `Buffer` into a `PlatformBuffer`

`copyToPlatformBuffer` copies through a **peek**, so the original kotlinx-io `Buffer` is not consumed.
The result is positioned for reading. Pass your own `factory` (e.g. `BufferFactory.managed()`) and
`byteOrder` when the defaults don't fit.

```kotlin
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.kotlinxio.copyToPlatformBuffer

val platformBuffer = kxBuffer.copyToPlatformBuffer(
    factory = BufferFactory.Default,
    byteOrder = ByteOrder.BIG_ENDIAN,
)
val header = platformBuffer.readInt()
```

## Lifetime rules

The `asRawSource()` / `asRawSink()` views are **thin adapters over the live buffer** — they don't own
it. Two rules:

- **Don't let a view outlive its buffer.** If the buffer is pooled or deterministic, the view must not
  be used after the buffer is released or freed. A view over a released
  [pooled buffer](./buffer-pooling) fails fast rather than reading another acquirer's bytes.
- **Views are single-buffer.** A `RawSink` view fills exactly one buffer; once the buffer is full,
  further writes overflow. For a growing stream, drain to a `copyTo*` snapshot or a larger allocation.

Using a view after its buffer is gone throws `IllegalStateException` (`"asRawSource() view has been
closed"` / `"asRawSink() view has been closed"`).

## See also

- [Okio Interop](./okio-interop) — the same bridge shape for Okio `Source` / `Sink`
- [Ktor Interop](./ktor-interop) — built on top of this module for Ktor channels
- [Platform Interop](./platform-interop) — native `toNativeData()` handles for zero-copy platform APIs
