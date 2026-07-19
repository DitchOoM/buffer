---
sidebar_position: 12
title: Ktor Interop
---

# Ktor Interop

The `buffer-ktor` module connects `PlatformBuffer` and [`buffer-codec`](./protocol-codecs) to
[Ktor 3](https://ktor.io):

- **Channel bridges** — move bytes between `ByteReadChannel` / `ByteWriteChannel` and `PlatformBuffer`.
- **`BufferCodecConverter`** — a Ktor `ContentConverter` that (de)serializes a `@ProtocolMessage`
  codec type over a binary content type via `ContentNegotiation`.
- **WebSocket masking** — RFC 6455 frame masking backed by the buffer library's SIMD-accelerated
  `xorMask`.

## Installation

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-ktor:<latest-version>")
}
```

`buffer-ktor` transitively brings in `buffer-kotlinx-io`, `buffer-codec`, and the Ktor 3.5.1 IO / HTTP
/ serialization artifacts. Ktor's own channels are built on kotlinx-io, so the channel bridges reuse
the [kotlinx-io bridge](./kotlinx-io-interop) internally.

## Channel bridges

### Read a channel into a buffer

`readRemainingBuffer` copies **all** remaining channel bytes into a fresh `PlatformBuffer` the caller
owns, positioned for reading.

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ktor.readRemainingBuffer

suspend fun handle(channel: ByteReadChannel) {
    val buffer = channel.readRemainingBuffer()       // owns its bytes, safe to retain
    val messageType = buffer.readByte()
    // ...
}
```

### Write a buffer to a channel

`writeBuffer` copies a buffer's remaining bytes to a `ByteWriteChannel` and flushes. It takes a
non-destructive snapshot, so **the buffer's position is unchanged** and it stays readable afterwards.

```kotlin
import com.ditchoom.buffer.ktor.writeBuffer

suspend fun send(channel: ByteWriteChannel, payload: ReadBuffer) {
    channel.writeBuffer(payload)                     // payload.position() unchanged
}
```

## Codec content negotiation

`BufferCodecConverter` binds **one codec per message type** to a binary content type. Register it in
the `ContentNegotiation` plugin; encode/decode then happen automatically on `call.receive<T>()` /
`call.respond(value)`.

```kotlin
import com.ditchoom.buffer.ktor.BufferCodecConverter
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.* // register(...)
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

install(ContentNegotiation) {
    register(ContentType.Application.OctetStream, BufferCodecConverter(DeviceReportCodec))
}

routing {
    post("/report") {
        val report = call.receive<DeviceReport>()   // decoded via DeviceReportCodec
        call.respond(report)                         // encoded via DeviceReportCodec
    }
}
```

The reified `BufferCodecConverter(codec)` factory infers the message `KClass` for you. You can also
supply a `factory`, `encodeContext`, and `decodeContext` to control allocation and thread typed
[codec context](./protocol-codecs) into encode/decode:

```kotlin
BufferCodecConverter(
    codec = DeviceReportCodec,
    factory = BufferFactory.managed(),
    decodeContext = DecodeContext.Empty.with(MaxSizeKey, 1_000_000),
)
```

`serialize` returns `null` for any type other than the one it's bound to, so it composes with other
converters registered on the same content type.

:::warning Codec ownership contract
`deserialize` frees its input buffer as soon as `decode` returns — **including when decode throws**.
A codec bound to this converter must return a value that *owns* its data, not one that aliases the
input buffer. Follow a canonical decode pattern (decode straight into a typed value, copy into a
consumer-owned `PlatformBuffer` via `write(source)`, or copy into a `ByteArray` via
`copyToByteArray(n)`). A codec returning a `slice()` / `readBytes(n)` view of the input will observe
freed memory once `deserialize` returns. See [Protocol Codecs](./protocol-codecs) for the patterns.
:::

### Encode a codec value to a buffer directly

Outside content negotiation, `encodeToPlatformBuffer` turns any `Encoder<T>` value into a fresh,
read-positioned `PlatformBuffer`. Exact-size encoders allocate once; back-patch encoders start from an
estimate and grow on overflow (capped at 1 GiB), and the buffer is freed on any encode failure.

```kotlin
import com.ditchoom.buffer.ktor.encodeToPlatformBuffer

val buffer = DeviceReportCodec.encodeToPlatformBuffer(report)
channel.writeBuffer(buffer)
buffer.freeNativeMemory()
```

## WebSocket frame masking

RFC 6455 requires client→server frames to be XOR-masked with a 4-byte key. These helpers delegate to
the buffer library's SIMD-accelerated `xorMask` / `xorMaskCopy`.

### Mask in place

`applyWebSocketMask` masks a buffer's remaining bytes in place. The XOR transform is symmetric — the
same call both masks and unmasks. Position and limit are unchanged.

```kotlin
import com.ditchoom.buffer.ktor.applyWebSocketMask

frame.applyWebSocketMask(maskingKey)                 // mask
frame.applyWebSocketMask(maskingKey)                 // unmask (symmetric)
```

Pass `maskOffset` (0–3) to continue a mask across fragmented frames.

### Mask and write in one pass

`writeMaskedWebSocketPayload` fuses the copy and the mask into a single pass into a scratch buffer,
writes the masked bytes to the channel, and frees the scratch. **The payload is not mutated** and its
position is unchanged, so it stays reusable.

```kotlin
import com.ditchoom.buffer.ktor.writeMaskedWebSocketPayload

suspend fun sendMasked(channel: ByteWriteChannel, payload: ReadBuffer, key: Int) {
    channel.writeMaskedWebSocketPayload(payload, maskingKey = key)
}
```

## See also

- [Protocol Codecs](./protocol-codecs) — `@ProtocolMessage` codecs used by `BufferCodecConverter`
- [kotlinx-io Interop](./kotlinx-io-interop) — the bridge Ktor's channels are built on
- [Stream Processing](./stream-processing) — framing codec messages off a chunked byte stream
