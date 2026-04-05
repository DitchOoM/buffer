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
