---
sidebar_position: 4
title: "Real Protocols: RFC-Compliant Examples"
---

# Real Protocols

These are real protocol implementations matching their RFC wire formats exactly. Each one compiles, round-trips, and generates frame detection automatically. No external dependencies — just annotated data classes.

## DNS Header (RFC 1035 &sect;4.1.1)

The DNS header is 12 bytes with bit-packed flags in a single UShort:

```
                                1  1  1  1  1  1
  0  1  2  3  4  5  6  7  8  9  0  1  2  3  4  5
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                      ID                         |
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE     |
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    QDCOUNT                       |
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    ANCOUNT                       |
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    NSCOUNT                       |
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    ARCOUNT                       |
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
```

**The implementation:**

```kotlin
@JvmInline
value class DnsFlags(val raw: UShort) {
    val qr: Boolean get() = (raw.toInt() shr 15) and 1 == 1
    val opcode: Int get() = (raw.toInt() shr 11) and 0xF
    val aa: Boolean get() = (raw.toInt() shr 10) and 1 == 1
    val tc: Boolean get() = (raw.toInt() shr 9) and 1 == 1
    val rd: Boolean get() = (raw.toInt() shr 8) and 1 == 1
    val ra: Boolean get() = (raw.toInt() shr 7) and 1 == 1
    val rcode: Int get() = raw.toInt() and 0xF
}

@ProtocolMessage
data class DnsHeader(
    val id: UShort,
    val flags: DnsFlags,
    val qdCount: UShort,
    val anCount: UShort,
    val nsCount: UShort,
    val arCount: UShort,
)
```

**That's the entire DNS header implementation.** 18 lines. The generated `DnsHeaderCodec` gives you:
- `encode` / `decode` — batch-optimized, reads all 12 bytes in grouped operations
- `sizeOf` — returns `SizeEstimate.Exact(12)`, always
- `peekFrameSize` — returns `PeekResult.Size(12)`, always (fixed-size)
- `testRoundTrip` — one-call verification that encode → decode produces the original value

The bit-packed flags are a `value class` wrapping a `UShort` — zero allocation at runtime. The codec reads 2 bytes and wraps them. Your application code accesses `header.flags.qr`, `header.flags.opcode`, etc. with no manual bit manipulation at the call site.

---

## WebSocket Frame (RFC 6455 &sect;5.2)

The WebSocket frame header has conditional fields — the masking key is only present when the mask bit is set:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Extended payload length continued, if payload len == 127   |
+ - - - - - - - - - - - - - - - +-------------------------------+
|                               |Masking-key, if MASK set to 1   |
+-------------------------------+-------------------------------+
| Masking-key (continued)       |          Payload Data         |
+-------------------------------- - - - - - - - - - - - - - - - +
```

**The implementation:**

```kotlin
@JvmInline
value class WsHeaderByte1(val raw: UByte) {
    val fin: Boolean get() = (raw.toInt() shr 7) and 1 == 1
    val rsv1: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val rsv2: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val rsv3: Boolean get() = (raw.toInt() shr 4) and 1 == 1
    val opcode: Int get() = raw.toInt() and 0xF
}

@JvmInline
value class WsHeaderByte2(val raw: UByte) {
    val mask: Boolean get() = (raw.toInt() shr 7) and 1 == 1
    val payloadLengthCode: Int get() = raw.toInt() and 0x7F
}

@JvmInline
value class WsMaskingKey(val raw: UInt)

@ProtocolMessage
data class WsMaskedFrame(
    val byte1: WsHeaderByte1,
    val byte2: WsHeaderByte2,
    @WhenTrue("byte2.mask") val maskingKey: WsMaskingKey? = null,
    @RemainingBytes val payload: String,
)
```

The `@WhenTrue("byte2.mask")` annotation tells the codec: "only read the masking key if the mask bit is set in byte2." The generated codec peeks into the value class property at runtime — no manual `if` statement, no forgotten null check. The compiler enforces that `maskingKey` is nullable because it's conditional.

---

## TLS Record Layer (RFC 5246 &sect;6.2.1)

The TLS record layer wraps every TLS message. The first byte is the content type, which determines what kind of record follows. This is sealed dispatch with `@DispatchOn`:

```
struct {
    ContentType type;           // 1 byte: 20=ChangeCipherSpec, 21=Alert, 22=Handshake, 23=AppData
    ProtocolVersion version;    // 2 bytes
    uint16 length;              // 2 bytes
    opaque fragment[length];    // variable
} TLSPlaintext;
```

**The implementation:**

```kotlin
@JvmInline
@ProtocolMessage
value class TlsContentType(val raw: UByte) {
    @DispatchValue
    val type: Int get() = raw.toInt()

    companion object {
        val CHANGE_CIPHER_SPEC = TlsContentType(20u)
        val ALERT = TlsContentType(21u)
        val HANDSHAKE = TlsContentType(22u)
        val APPLICATION_DATA = TlsContentType(23u)
    }
}

@ProtocolMessage
data class TlsProtocolVersion(val major: UByte, val minor: UByte) {
    companion object {
        val TLS_1_2 = TlsProtocolVersion(3u, 3u)
    }
}

@DispatchOn(TlsContentType::class)
@ProtocolMessage
sealed interface TlsRecord {
    @PacketType(20) @ProtocolMessage
    data class ChangeCipherSpec(
        val version: TlsProtocolVersion,
        val length: UShort,
        val message: UByte,
    ) : TlsRecord

    @PacketType(21) @ProtocolMessage
    data class Alert(
        val version: TlsProtocolVersion,
        val length: UShort,
        val level: UByte,
        val description: UByte,
    ) : TlsRecord

    @PacketType(22) @ProtocolMessage
    data class Handshake<@Payload P>(
        val version: TlsProtocolVersion,
        val length: UShort,
        @LengthFrom("length") val fragment: P,
    ) : TlsRecord

    @PacketType(23) @ProtocolMessage
    data class ApplicationData<@Payload P>(
        val version: TlsProtocolVersion,
        val length: UShort,
        @LengthFrom("length") val fragment: P,
    ) : TlsRecord
}
```

One sealed interface. The generated `TlsRecordCodec`:
- Reads the `TlsContentType` byte
- Dispatches to the correct variant's codec
- `Handshake` and `ApplicationData` use `@Payload` — the encrypted/encoded fragment bytes are passed to your decode lambda. You control what happens with the payload (decrypt it, parse it with another codec, pass it to a TLS library).
- `when (record)` in your code is exhaustive — the compiler ensures you handle every content type.

---

## PNG Chunks (PNG Specification &sect;5.3)

PNG files are a sequence of chunks, each with a 4-byte length, 4-byte type (ASCII FourCC), data, and CRC. The type determines the chunk structure. This uses a **data class discriminator** — the dispatch value comes from a multi-field header, not a single byte:

```
+---------+---------+------------------+---------+
| Length  |  Type   |      Data        |   CRC   |
| 4 bytes | 4 bytes | (length) bytes   | 4 bytes |
+---------+---------+------------------+---------+
```

**The implementation:**

```kotlin
@ProtocolMessage
data class PngChunkHeader(val length: UInt, val type: UInt) {
    @DispatchValue
    val chunkType: Int get() = type.toInt()
}

@DispatchOn(PngChunkHeader::class)
@ProtocolMessage
sealed interface PngChunk {
    @PacketType(0x49484452) @ProtocolMessage  // "IHDR"
    data class Ihdr(
        val header: PngChunkHeader,
        val width: UInt,
        val height: UInt,
        val bitDepth: UByte,
        val colorType: UByte,
        val compressionMethod: UByte,
        val filterMethod: UByte,
        val interlaceMethod: UByte,
        val crc: UInt,
    ) : PngChunk

    @PacketType(0x49454E44) @ProtocolMessage  // "IEND"
    data class Iend(
        val header: PngChunkHeader,
        val crc: UInt,
    ) : PngChunk
}
```

The `@DispatchOn(PngChunkHeader::class)` tells the codec: "read the header (8 bytes), extract `chunkType` from it, dispatch." The header is a data class with two fields — the codec peeks both `length` and `type` byte-by-byte for frame detection, then constructs the header during decode.

`@PacketType(0x49484452)` — that's "IHDR" as a big-endian 4-byte integer. The compiler validates that the value fits in the discriminator's type.

---

## MQTT CONNECT (RFC 3.1.1 &sect;3.1)

MQTT CONNECT has conditional fields controlled by flag bits, plus generic payload fields for will message and password that can carry any format:

```kotlin
@JvmInline
value class ConnectFlags(val raw: UByte) {
    val cleanSession: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1
}

@ProtocolMessage
data class MqttConnect<@Payload WP, @Payload PP>(
    @LengthPrefixed val protocolName: String,
    val protocolLevel: UByte,
    val flags: ConnectFlags,
    val keepAlive: UShort,
    @LengthPrefixed val clientId: String,
    @WhenTrue("flags.willFlag") @LengthPrefixed val willTopic: String? = null,
    @WhenTrue("flags.willFlag") @LengthPrefixed val willPayload: WP? = null,
    @WhenTrue("flags.usernameFlag") @LengthPrefixed val username: String? = null,
    @WhenTrue("flags.passwordFlag") @LengthPrefixed val password: PP? = null,
)
```

This is 14 lines for one of the most complex packet types in MQTT. The generated codec handles:
- Reading flag bits from a value class property to decide which fields are present
- Length-prefixed strings with 2-byte prefixes
- Two generic `@Payload` type parameters for will payload and password — your application decides the format
- `peekFrameSize` — automatically calculates frame size by peeking the flags and length prefixes
- Exhaustive null safety — `willTopic` is `String?` because the compiler knows it's conditional

---

## HTTP/2 Frame (RFC 7540 &sect;4.1)

Every HTTP/2 frame starts with a 9-byte header. The length field is 3 bytes (`@WireBytes(3)`), and the frame type byte dispatches to the correct payload structure:

```
+-----------------------------------------------+
|                 Length (24)                     |
+---------------+---------------+---------------+
|   Type (8)    |   Flags (8)   |
+-+-------------+---------------+---------------+
|R|                 Stream Identifier (31)       |
+-+---------------------------------------------+
|                 Frame Payload (0...)          ...
+-----------------------------------------------+
```

**The implementation:**

```kotlin
@JvmInline
@ProtocolMessage
value class Http2FrameType(val raw: UByte) {
    @DispatchValue
    val type: Int get() = raw.toInt()

    companion object {
        val DATA = Http2FrameType(0x0u)
        val HEADERS = Http2FrameType(0x1u)
        val PRIORITY = Http2FrameType(0x2u)
        val RST_STREAM = Http2FrameType(0x3u)
        val SETTINGS = Http2FrameType(0x4u)
        val PING = Http2FrameType(0x6u)
        val GOAWAY = Http2FrameType(0x7u)
        val WINDOW_UPDATE = Http2FrameType(0x8u)
    }
}

@JvmInline
value class Http2StreamId(val raw: UInt) {
    /** Stream ID is 31 bits — the reserved bit (MSB) is masked off. */
    val id: Int get() = (raw.toInt() and 0x7FFFFFFF)
}

@DispatchOn(Http2FrameType::class)
@ProtocolMessage
sealed interface Http2Frame {
    @PacketType(0x0) @ProtocolMessage
    data class Data<@Payload P>(
        val flags: UByte,
        val streamId: Http2StreamId,
        @WireBytes(3) val length: Int,
        @LengthFrom("length") val payload: P,
    ) : Http2Frame

    @PacketType(0x4) @ProtocolMessage
    data class Settings(
        val flags: UByte,
        val streamId: Http2StreamId,
    ) : Http2Frame

    @PacketType(0x6) @ProtocolMessage
    data class Ping(
        val flags: UByte,
        val streamId: Http2StreamId,
        val opaqueData: Long,  // always 8 bytes
    ) : Http2Frame

    @PacketType(0x8) @ProtocolMessage
    data class WindowUpdate(
        val flags: UByte,
        val streamId: Http2StreamId,
        val windowSizeIncrement: UInt,
    ) : Http2Frame
}
```

The 3-byte length field uses `@WireBytes(3)` — the codec reads exactly 3 bytes and assembles them into an `Int`. The reserved bit in the stream identifier is handled by the `Http2StreamId` value class — the codec reads 4 bytes, the `id` property masks off the MSB. Zero allocation, zero ambiguity.

---

## BLE ATT Protocol (Bluetooth Core Spec Vol 3 Part F)

The Attribute Protocol (ATT) is the foundation of Bluetooth Low Energy communication. Every PDU starts with an opcode byte that dispatches to the correct structure. Mobile developers on Android and iOS deal with this constantly:

```
+----------+-----------------------------------+
| Opcode   |     Parameters                    |
| (1 byte) |     (variable)                    |
+----------+-----------------------------------+
```

**The implementation:**

```kotlin
@JvmInline
value class AttHandle(val raw: UShort)

@ProtocolMessage
sealed interface AttPdu {
    /** Error Response (0x01) — returned when a request fails. */
    @PacketType(0x01) @ProtocolMessage
    data class ErrorResponse(
        val requestOpcode: UByte,
        val handle: AttHandle,
        val errorCode: UByte,
    ) : AttPdu

    /** Read Request (0x0A) — client reads an attribute by handle. */
    @PacketType(0x0A) @ProtocolMessage
    data class ReadRequest(
        val handle: AttHandle,
    ) : AttPdu

    /** Read Response (0x0B) — server returns the attribute value. */
    @PacketType(0x0B) @ProtocolMessage
    data class ReadResponse(
        @RemainingBytes val value: String,
    ) : AttPdu

    /** Write Request (0x12) — client writes an attribute value. */
    @PacketType(0x12) @ProtocolMessage
    data class WriteRequest(
        val handle: AttHandle,
        @RemainingBytes val value: String,
    ) : AttPdu

    /** Handle Value Notification (0x1B) — server pushes a value change. */
    @PacketType(0x1B) @ProtocolMessage
    data class Notification(
        val handle: AttHandle,
        @RemainingBytes val value: String,
    ) : AttPdu
}
```

This replaces the manual byte parsing that every BLE library does internally. The sealed dispatch means your `when (pdu)` is exhaustive — add a new PDU type and the compiler flags every handler that needs updating. `AttHandle` as a value class gives type safety (can't accidentally pass a raw `UShort` where a handle is expected) with zero runtime overhead.

---

## Zero-Copy PNG Decode with Platform APIs

This is where everything comes together: parse a binary format with the codec, then hand the **same memory** to a platform-native decoder. No `ByteArray` copies anywhere in the pipeline.

### The problem with typical image loading

Most image libraries follow this path:

```
Network I/O → byte[] copy → library buffer → byte[] copy → platform decoder → Bitmap
```

For example, a typical Kotlin image loading pipeline:
1. Read from socket into a library-internal buffer (okio Segment, ByteArray, etc.)
2. **Copy** to a contiguous `ByteArray` for the decoder
3. `BitmapFactory.decodeByteArray(bytes)` on Android, `UIImage(data: NSData(bytes:length:))` on iOS
4. The decoder may copy again internally depending on how you passed the data

Each copy doubles memory pressure and costs CPU time proportional to image size. For a 5MB camera photo, that's 10-15MB of transient allocations just to get it to the decoder.

### The zero-copy path

With buffer, network bytes land directly in platform-native memory. The codec reads from that memory without copying. The platform decoder receives the **same memory handle**:

```
Network I/O → native buffer → codec parses (zero-copy) → platform decoder (zero-copy) → Bitmap
```

**Step 1: Define the PNG chunk structure** (already shown above)

```kotlin
@DispatchOn(PngChunkHeader::class)
@ProtocolMessage
sealed interface PngChunk {
    @PacketType(0x49484452) @ProtocolMessage
    data class Ihdr(
        val header: PngChunkHeader,
        val width: UInt, val height: UInt,
        val bitDepth: UByte, val colorType: UByte,
        val compressionMethod: UByte, val filterMethod: UByte,
        val interlaceMethod: UByte, val crc: UInt,
    ) : PngChunk
    // ... other chunk types
}
```

**Step 2: Parse and inspect — zero-copy reads from native memory**

```kotlin
// Buffer arrived from network I/O — already in native memory
// (DirectByteBuffer on JVM, NSData on Apple, ArrayBuffer on JS)
val ihdr = PngChunkCodec.decode(buffer) as PngChunk.Ihdr

// Type-safe access to image metadata — no pixel decoding yet
println("${ihdr.width}x${ihdr.height}, depth=${ihdr.bitDepth}")

// Validate before spending CPU on decode
require(ihdr.width <= 4096u && ihdr.height <= 4096u) { "Image too large" }
```

**Step 3: Hand the same memory to the platform decoder — zero-copy**

```kotlin
// expect declaration — each platform uses its native image API
expect fun ReadBuffer.decodePlatformImage(): PlatformImage
```

```kotlin
// Android — BitmapFactory reads directly from the ByteBuffer
actual fun ReadBuffer.decodePlatformImage(): PlatformImage {
    val byteBuffer = toNativeData().byteBuffer  // zero-copy: same native memory
    return BitmapFactory.decodeByteBuffer(byteBuffer)
}
```

```kotlin
// Apple — UIImage reads directly from NSData
actual fun ReadBuffer.decodePlatformImage(): PlatformImage {
    val nsData = toNativeData().nsData  // zero-copy: same native memory
    return UIImage(data = nsData)
}
```

```kotlin
// JS — createImageBitmap reads directly from ArrayBuffer
actual fun ReadBuffer.decodePlatformImage(): PlatformImage {
    val arrayBuffer = toNativeData().arrayBuffer  // zero-copy: same native memory
    val blob = Blob(arrayOf(arrayBuffer), BlobPropertyBag("image/png"))
    return createImageBitmap(blob).await()
}
```

**The key insight:** `toNativeData()` doesn't copy — it returns the buffer's existing native memory handle. On JVM that's the `java.nio.ByteBuffer` the data already lives in. On Apple it's the `NSData` the buffer already wraps. The platform decoder reads from the same memory the network I/O wrote to.

### Comparison: typical pipeline vs buffer

Tracing every copy in a typical `ByteArray`-based image loading path vs the buffer path:

| Step | Typical `ByteArray` pipeline | buffer pipeline |
|---|---|---|
| Network → process | kernel buffer → DirectByteBuffer | kernel buffer → DirectByteBuffer |
| | *copy 1: native → native* | *copy 1: native → native (unavoidable)* |
| Read into library | DirectByteBuffer → `ByteArray` | codec reads in-place from DirectByteBuffer |
| | *copy 2: native → managed heap* | *zero-copy* |
| Parse headers | manual parsing on `ByteArray` | codec reads in-place |
| | *no copy, but data is on the wrong side of the JNI boundary* | *zero-copy, data stays in native memory* |
| Decode image (Android) | `BitmapFactory.decodeByteArray(bytes)` | `BitmapFactory.decodeByteBuffer(byteBuffer)` |
| | *copy 3: JNI copies managed heap → native heap for Skia* | *zero-copy: Skia reads the DirectByteBuffer directly* |
| Decode image (Apple) | `NSData(bytes:length:)` from Kotlin `ByteArray` | `buffer.toNativeData().nsData` |
| | *copy 3: managed → NSData allocation* | *zero-copy: same NSMutableData the buffer already wraps* |
| Decode image (JS) | `Uint8Array` from `ByteArray` | `buffer.toNativeData().arrayBuffer` |
| | *copy 3: Kotlin heap → JS ArrayBuffer* | *zero-copy: same ArrayBuffer the buffer already wraps* |
| **Total copies** | **3** (native→heap, heap→parse, heap→native for decoder) | **1** (kernel→DirectByteBuffer, unavoidable) |

The hidden cost is **copy 3** — crossing the managed/native boundary. `BitmapFactory.decodeByteArray()` can't pass a Java heap `byte[]` pointer to the native Skia decoder. The JNI bridge must copy the entire image to native memory first. On Apple, `NSData(bytes:length:)` from a Kotlin `ByteArray` copies managed bytes into a new `NSData` allocation.

### Why libraries like Coil force copies by design

Coil is built on OkHttp + okio. okio's `Buffer` is a linked list of pooled 8KB `byte[]` **Segments** — managed heap memory. This is an intentional design choice that optimizes for efficient streaming and segment reuse, but it means every path to a native decoder requires a managed→native copy:

```
Coil image loading path (Android):

1. OkHttp reads network data → okio Segments (pooled byte[] on JVM heap)    ← copy 1
2. Coil reads from BufferedSource → contiguous ByteArray                     ← copy 2 (scatter→gather)
3. BitmapFactory.decodeByteArray() → JNI copies heap → native for Skia      ← copy 3
4. Skia decodes from native memory → Bitmap pixels                           ← decode

Even the disk cache path:
1. Read cached file → okio Segments (managed heap)                           ← copy 1
2. Segments → ByteArray or InputStream                                       ← copy 2
3. BitmapFactory → JNI copies heap → native                                  ← copy 3
```

This isn't a bug — it's a consequence of okio's architecture. okio Segments are managed `byte[]` arrays. They can't be passed directly to native decoders because the JVM garbage collector can relocate them at any time. The JNI boundary requires either pinning (expensive, not available in all contexts) or copying.

**The buffer approach avoids this entirely** because data starts in native memory and stays there:

```
buffer image loading path:

1. Network I/O → DirectByteBuffer (native memory)                           ← copy 1 (kernel only)
2. Codec parses PNG headers in-place                                         ← zero-copy
3. BitmapFactory.decodeByteBuffer(directBuffer) → Skia reads native directly ← zero-copy
4. Skia decodes → Bitmap pixels                                              ← decode
```

`DirectByteBuffer` has a stable native memory address that Skia can read without JNI copying. On Apple, `NSMutableData` is already native memory — `UIImage(data:)` reads it directly. On JS, `ArrayBuffer` is already the browser's native memory.

### Memory and performance impact

For a 5MB image, tracing peak memory during decode:

| Allocation | okio/Coil path | buffer path |
|---|---|---|
| okio Segments (managed heap) | ~5MB | -- |
| Contiguous ByteArray (managed heap) | ~5MB | -- |
| JNI native copy for Skia (native heap) | ~5MB | -- |
| DirectByteBuffer (native) | -- | ~5MB |
| **Peak image data in memory** | **~15MB** | **~5MB** |

The 10MB difference is transient — GC reclaims it after decode — but during decode all three allocations are live simultaneously. For 20 images in a gallery scroll, that's 200MB vs 100MB of transient memory pressure.

**Speed:** each 5MB `memcpy` costs ~1-2ms, but the larger cost is **cache pollution** — three 5MB copies flush 15MB of L2/L3 cache, evicting other hot data. GC pressure from the managed allocations adds latency spikes on top. Buffer eliminates 2 of 3 copies. For a single image the savings are ~2-5ms. At 60fps in a scroll, that's the margin between making frame budget and dropping frames.

### When does this matter?

For a single profile picture, the extra copies are negligible. But for:
- **Gallery scroll** — decoding 20+ images during a fling, each copy adds latency and GC pressure
- **Camera preview processing** — 30fps × 5MB frames = 150MB/s of unnecessary copies
- **Server-side thumbnailing** — thousands of images per second, copies dominate CPU time
- **BLE image transfer** — constrained devices where every byte of memory matters
- **Streaming video thumbnails** — continuous decode where GC pauses cause visible jank

In these cases, eliminating 2 copies per image is the difference between smooth and janky.

### Honest tradeoffs

Buffer's approach isn't free — there are real tradeoffs to consider:

- **DirectByteBuffer allocation is slower than ByteArray** — ~1.3M ops/s vs ~7.6M ops/s on JVM. For many tiny buffers (< 1KB), managed heap is faster to allocate. For large buffers (images, audio, video), the allocation cost is dwarfed by the copy savings. Use `BufferPool` to amortize allocation cost in hot paths.
- **Native memory is not GC-managed** — if you forget to release pooled buffers, you leak native memory. okio's Segments are GC-safe by design. Buffer handles this with scoped APIs: `pool.withBuffer { }` and `BufferFactory.deterministic().allocate().use { }`.
- **okio is optimized for streaming small reads** — its segment pooling is very efficient for HTTP response parsing, line-by-line text processing, etc. Buffer is optimized for large contiguous data (images, protocol frames, audio/video).
- **Coil does far more than decode** — caching, resizing, transformations, lifecycle management, crossfade animations. The comparison here is specifically about the decode path. If you need Coil's feature set, use Coil. If you control the transport and need zero-copy decode, buffer gives you that layer.

With buffer, the data **never leaves native memory**. It arrives in a `DirectByteBuffer` (JVM), `NSMutableData` (Apple), or `ArrayBuffer` (JS), the codec parses in-place, and the platform decoder reads from the same allocation. The only unavoidable copy is the kernel-to-userspace transfer that every networking stack does.

### Pool return for request-per-image workloads

For high-throughput image processing (thumbnailing service, gallery scroll), compose a `BufferFactory` with pooling. Library code accepts `BufferFactory` as a parameter — it doesn't know or care whether pooling is active:

```kotlin
// Compose once at the application level
val pool = BufferPool.MultiThreaded(defaultBufferSize = 65_536)
val factory = BufferFactory.Default.withPooling(pool)

// Library code accepts BufferFactory — pooling is transparent
class ImageDecoder(private val factory: BufferFactory = BufferFactory.Default) {
    fun decode(imageBytes: Int): PlatformImage {
        val buffer = factory.allocate(imageBytes)
        try {
            socket.read(buffer)
            buffer.resetForRead()

            // Parse PNG header — zero-copy
            val ihdr = PngChunkCodec.decode(buffer) as PngChunk.Ihdr
            println("${ihdr.width}x${ihdr.height}")

            // Decode — zero-copy, platform-native
            buffer.resetForRead()
            return buffer.decodePlatformImage()
        } finally {
            buffer.freeNativeMemory() // returns to pool if pooled, frees if not
        }
    }
}

// Caller controls allocation strategy
val decoder = ImageDecoder(factory) // pooled — high throughput, no GC
val decoder = ImageDecoder()        // default — simpler, GC-managed
```

The `BufferFactory` abstraction means your library code works identically whether the caller uses pooling, managed memory, shared memory (Android IPC), or deterministic cleanup. The allocation strategy is a caller decision, not a library decision.

---

## What You Get for Free

Every example above generates all of this from the annotations alone:

| Capability | What it does | Why it matters |
|---|---|---|
| **`encode` / `decode`** | Batch-optimized read/write in field order | Consecutive fixed-size fields are grouped into bulk operations |
| **`sizeOf`** | Returns `SizeEstimate.Exact(n)` for buffer pre-allocation | Zero wasted bytes, no growable buffer overhead |
| **`peekFrameSize`** | Returns `PeekResult.Size(n)` by peeking at a stream | Frame detection without consuming bytes — the streaming loop writes itself |
| **`testRoundTrip`** | One-call encode → decode → assert equality | Catches field-order mismatches, type mismatches, off-by-one errors |
| **Sealed dispatch** | Reads discriminator, dispatches to correct variant | Exhaustive `when` in your code — compiler catches missing cases |
| **Context forwarding** | `DecodeContext` / `EncodeContext` flows through the chain | Pass allocators, config, version info without global state |

### Zero-copy

The buffer layer delegates to platform-native memory on every target:

| Platform | Direct buffer | What it wraps |
|---|---|---|
| JVM | `DirectJvmBuffer` | `java.nio.ByteBuffer` |
| Android | `DirectJvmBuffer` | `java.nio.ByteBuffer` |
| Apple | `MutableDataBuffer` | `NSMutableData` |
| JS | `JsBuffer` | `ArrayBuffer` / `SharedArrayBuffer` |
| WASM | `LinearBuffer` | Native WASM linear memory |
| Linux | `NativeBuffer` | `malloc` / `free` |

Codecs read/write directly on native memory. No intermediate `ByteArray` copies unless you explicitly ask for one.

### Type safety

- Sealed interfaces + `@PacketType` = exhaustive dispatch. Add a new packet type → the compiler tells you every `when` that needs updating.
- `@WhenTrue` conditional fields are forced nullable — you can't forget the null check.
- `@Payload` type parameters are generic — the codec handles framing, your lambda handles format. Wire format and payload format are decoupled at the type level.
- `SizeEstimate` and `PeekResult` are sealed — exhaustive `when` forces you to handle both cases. No forgotten null checks.

### Performance

- **Batch optimization**: consecutive fixed-size fields are read in a single bulk operation
- **Value classes**: bit-packed headers like `DnsFlags`, `ConnectFlags`, `WsHeaderByte1` inline to their primitive type at runtime — zero allocation
- **SIMD-accelerated operations**: `contentEquals`, `mismatch`, `indexOf`, `xorMask` use platform-optimal implementations (SIMD on native, VarHandle on JVM 11+)
- **Buffer pooling**: `BufferPool` eliminates GC pressure in hot paths
