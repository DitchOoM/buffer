---
sidebar_position: 3
title: Protocol Codecs
---

# Protocol Codecs

Annotate a data class, get a type-safe codec at compile time — round-trip-safe, with zero manual field wiring.

## Why Protocol Codecs?

Hand-written protocol parsers break silently as protocols grow:

- **Field order mismatches** between encode and decode go undetected until runtime
- **Missing fields** in one direction cause subtle data corruption
- **No round-trip guarantee** without manual test discipline
- **Tedious boilerplate** — every field needs matching read/write calls in exact order

## Installation

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation("com.ditchoom:buffer-codec:<latest-version>")
    ksp("com.ditchoom:buffer-codec-processor:<latest-version>")
}
```

## Quick Start

Define your wire format as a data class:

```kotlin
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.LengthPrefixed

@ProtocolMessage
data class DeviceReport(
    val protocolVersion: UByte,       // 1 byte
    val deviceType: UShort,           // 2 bytes
    val sequenceNumber: UInt,         // 4 bytes
    val timestamp: Long,              // 8 bytes
    val latitude: Double,             // 8 bytes
    val longitude: Double,            // 8 bytes
    val altitude: Float,              // 4 bytes
    val batteryLevel: UByte,          // 1 byte
    val signalStrength: Short,        // 2 bytes
    @LengthPrefixed val deviceName: String,
)
```

The KSP processor generates `DeviceReportCodec` — an `object` implementing
`Codec<DeviceReport>` with `encode`, `decode`, `wireSize`, and (when the wire
format allows it) `peekFrameSize`:

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

val buffer = BufferFactory.Default.allocate(128)
DeviceReportCodec.encode(buffer, report, EncodeContext.Empty)
buffer.resetForRead()
val decoded = DeviceReportCodec.decode(buffer, DecodeContext.Empty)
```

`EncodeContext.Empty` / `DecodeContext.Empty` are the no-configuration
contexts. See [CodecContext](#codeccontext--runtime-configuration) for passing
typed runtime configuration through the codec chain.

> **Note:** Generated code appears after `./gradlew build`. IDE autocomplete for generated codecs requires an initial build.

### What you'd have to write manually

Without code gen, this 10-field message requires writing every field in exact order — twice. One mismatch (e.g., swapping `latitude`/`longitude`, or reading a `Float` where you wrote a `Double`) silently corrupts data:

```kotlin
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.*

// 30+ lines of error-prone boilerplate
object DeviceReportCodec : Codec<DeviceReport> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): DeviceReport {
        val protocolVersion = buffer.readUByte()
        val deviceType = buffer.readUShort()
        val sequenceNumber = buffer.readUInt()
        val timestamp = buffer.readLong()
        val latitude = buffer.readDouble()
        val longitude = buffer.readDouble()
        val altitude = buffer.readFloat()
        val batteryLevel = buffer.readUByte()
        val signalStrength = buffer.readShort()
        val nameLength = buffer.readUShort().toInt()
        val deviceName = buffer.readString(nameLength)
        return DeviceReport(
            protocolVersion, deviceType, sequenceNumber, timestamp,
            latitude, longitude, altitude, batteryLevel, signalStrength, deviceName,
        )
    }

    override fun encode(buffer: WriteBuffer, value: DeviceReport, context: EncodeContext) {
        buffer.writeUByte(value.protocolVersion)
        buffer.writeUShort(value.deviceType)
        buffer.writeUInt(value.sequenceNumber)
        buffer.writeLong(value.timestamp)
        buffer.writeDouble(value.latitude)
        buffer.writeDouble(value.longitude)
        buffer.writeFloat(value.altitude)
        buffer.writeUByte(value.batteryLevel)
        buffer.writeShort(value.signalStrength)
        val nameBytes = value.deviceName.encodeToByteArray()
        buffer.writeUShort(nameBytes.size.toUShort())
        buffer.writeString(value.deviceName)
    }

    override fun wireSize(value: DeviceReport, context: EncodeContext): WireSize {
        val nameBytes = value.deviceName.encodeToByteArray().size
        return WireSize.Exact(1 + 2 + 4 + 8 + 8 + 8 + 4 + 1 + 2 + 2 + nameBytes)
    }
}
```

The generated version writes the same fields in the same order — but the order is derived from the constructor, so encode and decode can never drift apart.

### Round-Trip Testing

There is no built-in test helper. Verify that encode → decode reproduces the original value with a plain assertion:

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals

@Test
fun deviceReportRoundTrip() {
    val report = DeviceReport(
        protocolVersion = 1u,
        deviceType = 42u.toUShort(),
        sequenceNumber = 1000u,
        timestamp = 1710000000000L,
        latitude = 37.7749,
        longitude = -122.4194,
        altitude = 15.5f,
        batteryLevel = 87u,
        signalStrength = -65,
        deviceName = "sensor-north-1",
    )

    val buffer = BufferFactory.Default.allocate(128)
    DeviceReportCodec.encode(buffer, report, EncodeContext.Empty)
    buffer.resetForRead()
    val decoded = DeviceReportCodec.decode(buffer, DecodeContext.Empty)

    assertEquals(report, decoded)
}
```

To pin the exact wire bytes, compare the encoded buffer against a known
`ByteArray` (`buffer.readByteArray(buffer.remaining())` after `resetForRead()`).

## Annotation Reference

### `@LengthPrefixed` — Length-Prefixed Data

Reads/writes a length prefix followed by that many bytes. Default is a 2-byte big-endian prefix.

```kotlin
import com.ditchoom.buffer.codec.annotations.LengthPrefix

@ProtocolMessage
data class GreetingMessage(
    @LengthPrefixed val name: String,                          // 2-byte prefix (default)
    @LengthPrefixed(LengthPrefix.Byte) val nickname: String,   // 1-byte prefix (max 255 bytes)
    @LengthPrefixed(LengthPrefix.Int) val bio: String,         // 4-byte prefix
)
```

Accepted on `String` fields, on nested `@ProtocolMessage` fields (the prefix
carries the nested body's wire size), and on typed payloads via `@UseCodec`
(see [Typed Payloads](#typed-payloads-and-the-payload-marker)). All prefix
widths are big-endian (network byte order).

For a length carrier that sits immediately before its data, **prefer
`@LengthPrefixed` over a separate length field plus `@LengthFrom`** — a
standalone length field encodes the same quantity twice (the prefix and the
value's own `wireSize`), an impossible-state class the validator rejects.

### `@RemainingBytes` — Consume Remaining Bytes

Consumes the rest of the bounded buffer.

```kotlin
import com.ditchoom.buffer.codec.annotations.RemainingBytes

@ProtocolMessage
data class LogEntry(
    val level: UByte,
    @RemainingBytes val message: String,  // reads everything after the level byte
)
```

Accepted on:

- `String` — UTF-8 body consuming the rest of the buffer.
- `List<T>` where `T` is a `@ProtocolMessage` data class — loops, decoding
  nested-message bodies until the bound is reached.
- A typed payload via `@RemainingBytes @UseCodec(C::class)` — see
  [Typed Payloads](#typed-payloads-and-the-payload-marker).

`@RemainingBytes` is normally the last constructor parameter, but it may appear
**before trailing fixed-size fields** — the decoder subtracts the trailers'
summed wire bytes from `buffer.limit()` before reading the body:

```kotlin
@ProtocolMessage
data class TextWithChecksum(
    val tag: UByte,
    @RemainingBytes val text: String,   // bounded to limit() - 4 (the crc)
    val crc: UInt,                      // 4-byte fixed-size trailer
)
```

Variable-size trailers (`@LengthPrefixed`, `@LengthFrom`, another
`@RemainingBytes`, `@When`, `@UseCodec`) after a `@RemainingBytes` field are
rejected by the validator — the body decode would have no way to find its end.

The decoder reads against `buffer.limit()`. When the body byte count is carried
by framing outside the codec's view (MQTT's remaining-length variable-byte
integer, an HTTP/2 frame-length header), the caller — or an outer codec — is
responsible for narrowing `buffer.limit()` to the body's extent before decode.

### `@LengthFrom("field")` — Length from a Preceding Field

The byte length is determined by a numeric sibling field rather than an inline prefix.

```kotlin
import com.ditchoom.buffer.codec.annotations.LengthFrom

@ProtocolMessage
data class RemoteHeader(
    val payloadLength: UShort,     // consumer-visible — flow control, routing
    val flags: UByte,
    val correlationId: UInt,
    @LengthFrom("payloadLength") val payload: String,
)
```

Accepted on `String` fields, `List<T>` of `@ProtocolMessage` data classes, and
nested `@ProtocolMessage` fields. The referenced field must exist, come before
the annotated field, and resolve to a non-nullable numeric scalar — or, in the
dotted form `"sibling.property"`, to a `val` returning non-nullable `Int` on a
sibling value class.

`@LengthFrom` is the right tool when the length is **non-adjacent** (the
consumer cares about it as a number, or it is parsed by a different layer), or
when the prefix width is one `@LengthPrefixed` cannot express. Example: a TLS
1.3 handshake header (RFC 8446 §4) carries a 3-byte big-endian length:

```kotlin
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.WireBytes

@ProtocolMessage(wireOrder = Endianness.Big)
data class TlsHandshake(
    val msgType: UByte,
    @WireBytes(3) val length: UInt,                 // uint24
    @LengthFrom("length") val body: TlsHandshakeBody,
)
```

For an adjacent length carrier whose width is 1, 2, or 4 bytes, use
`@LengthPrefixed` instead — the validator rejects the redundant separate-field
shape for `String` and `List<T>` bodies.

### `@WireBytes(n)` — Custom Wire Width

Override the default wire size of a numeric field. Useful for protocols that use 3-byte, 5-byte, 6-byte, or 7-byte integers.

```kotlin
@ProtocolMessage
data class CompactHeader(
    @WireBytes(3) val length: Int,    // 3 bytes on the wire, decoded into Int
    @WireBytes(6) val offset: Long,   // 6 bytes on the wire, decoded into Long
)
```

Only applies to integer types. The width must be 1–8 and must not exceed the Kotlin type's natural size. Not allowed on `Float`, `Double`, or `Boolean`.

### `@WireOrder(order)` — Per-Field Byte Order

Overrides the byte order for a single field, taking precedence over the
message-level `@ProtocolMessage(wireOrder = ...)` setting. A field annotated
`@WireOrder` is encoded/decoded in that order **regardless of the
`ReadBuffer`/`WriteBuffer` byte order** — use it when a protocol mixes byte
orders within a single message (e.g., a big-endian magic number followed by
little-endian length fields).

```kotlin
@ProtocolMessage(wireOrder = Endianness.Little)
data class MixedHeader(
    @WireOrder(Endianness.Big) val magic: UInt,  // overrides to big-endian
    val length: UInt,                            // inherits little-endian
)
```

A real example: the RIFF container family (WAV, AVI, WebP) is little-endian,
but each chunk's 4-byte FourCC is four ASCII characters whose byte order must
be preserved on the wire — so the FourCC is read big-endian while the sizes
stay little-endian:

```kotlin
@ProtocolMessage(wireOrder = Endianness.Little)
data class RiffChunkHeader(
    @WireOrder(Endianness.Big) val fourCC: UInt,  // e.g. "fmt " — bytes in file order
    val chunkSize: UInt,                          // little-endian, per the RIFF spec
)
```

`@WireOrder` composes with `@WireBytes`: a custom-width field can carry a
per-field order too — e.g. `@WireOrder(Endianness.Little) @WireBytes(3)` for a
3-byte little-endian integer.

Only applies to multi-byte numeric types (`Short`, `UShort`, `Int`, `UInt`, `Long`, `ULong`, `Float`, `Double`). Single-byte types (`Byte`, `UByte`, `Boolean`) are unaffected.

The message-level `wireOrder` parameter on `@ProtocolMessage` sets the default for all fields, and defaults to `Endianness.Default` (use the buffer's byte order). On sealed interfaces, variants inherit the parent's `wireOrder` unless they override it.

### `@UseCodec(codec)` — Delegate to a Hand-Written Codec

Delegates a field to an existing `Codec` object. Use it for encodings the
built-in annotations don't cover — variable-byte integers, image-bitmap
parsers, per-charset string codecs, and the like. The referenced codec must be
a Kotlin `object` implementing `Codec<T>` (or `BoundingLengthCodec<UInt>` for
the length-prefixed-list shape).

**Bare scalar** — the codec reads directly from the buffer:

```kotlin
import com.ditchoom.buffer.codec.annotations.UseCodec

@ProtocolMessage
data class Message(
    @UseCodec(VariableIntCodec::class) val length: Int,
)
// Generated: val length = VariableIntCodec.decode(buffer, context)
```

**With a length annotation** — the codec receives a size-limited slice. Composes with `@LengthPrefixed`, `@RemainingBytes`, and `@LengthFrom`:

```kotlin
@ProtocolMessage
data class TaggedValue(
    val tag: UByte,
    val dataLength: Int,
    @UseCodec(RgbCodec::class) @LengthFrom("dataLength") val color: Rgb,
)
// Generated: narrow buffer.limit() by dataLength, run RgbCodec.decode, restore the limit
```

When the field's type is itself a `@ProtocolMessage`, **do not** use
`@UseCodec` — declare the field with that type directly and the processor
generates and wires the codec by convention. `@UseCodec` cannot
forward-reference a codec generated in the same compilation round.

`expect`/`actual` codec objects are supported: the processor emits a call by
simple name and the Kotlin linker resolves the platform actual.

See [Manual Codecs](#manual-codecs) for how to write the codec object itself.

### `@When("predicate")` — Conditional Fields

A field that is only present on the wire when a preceding Boolean predicate holds. The field must be nullable; `= null` as the constructor default is conventional.

```kotlin
import com.ditchoom.buffer.codec.annotations.When

@ProtocolMessage
data class OptionalPayload(
    val hasExtra: Boolean,
    @When("hasExtra") val extra: Int? = null,
)
```

The predicate grammar is deliberately narrow — no `&&` / `||`, no `!=`, no
method calls. Two forms are accepted:

- `"siblingField"` — a prior sibling `Boolean` constructor parameter.
- `"siblingField.property"` — a `Boolean`-returning `val` on a sibling
  `@JvmInline value class`.

The dotted form is how real protocols gate optional fields on a bit-packed
flags byte. MQTT v3.1.1 CONNECT (§3.1.2.3) packs presence bits into one byte —
model it as a value class whose getters name each bit:

```kotlin
@JvmInline
@ProtocolMessage
value class MqttConnectFlags(val raw: UByte) {
    val cleanSession: Boolean get() = (raw.toInt() and 0x02) != 0
    val willPresent: Boolean get() = (raw.toInt() and 0x04) != 0
    val passwordPresent: Boolean get() = (raw.toInt() and 0x40) != 0
    val usernamePresent: Boolean get() = (raw.toInt() and 0x80) != 0
}

// CONNECT (§3.1) carries the Will topic and user name only when the matching
// flag bit is set.
@ProtocolMessage
data class MqttConnect(
    @LengthPrefixed val protocolName: String,     // "MQTT"
    val protocolLevel: UByte,                     // 4 = v3.1.1
    val connectFlags: MqttConnectFlags,
    val keepAliveSeconds: UShort,
    @LengthPrefixed val clientId: String,
    @LengthPrefixed @When("connectFlags.willPresent") val willTopic: String? = null,
    @LengthPrefixed @When("connectFlags.usernamePresent") val username: String? = null,
)
```

The real CONNECT also gates a Will payload and a password the same way — both
binary, via `@When` combined with `@UseCodec` (see [Typed Payloads](#typed-payloads-and-the-payload-marker)).

A value-class getter is plain Kotlin: it can be a single-bit test, a
comparison, or a compound `&&` / `||` expression. The validator only sees a
`Boolean`-returning `val`, so conditions the narrow predicate grammar can't
express belong there.

When the predicate is `false`, the entire slot is skipped on the wire (zero
bytes, including any `@LengthPrefixed` prefix). When the predicate is `true`
and the value is `null`, encode throws `EncodeException`.

> Renamed from `@WhenTrue` in 5.0.0. Semantics unchanged.

### `@PacketType` + Sealed Interfaces — Auto-Dispatched Decode

Annotate a sealed interface with `@ProtocolMessage` and each variant with `@PacketType(value)` to generate a dispatch codec. The processor reads the type discriminator and delegates to the correct variant codec. Duplicate values are rejected at compile time.

```kotlin
import com.ditchoom.buffer.codec.annotations.PacketType

@ProtocolMessage
sealed interface Command {
    @ProtocolMessage
    @PacketType(0x01)
    data class Ping(val timestamp: Long) : Command

    @ProtocolMessage
    @PacketType(0x02)
    data class Echo(
        @LengthPrefixed val message: String,
    ) : Command
}
```

The processor generates:

- `PingCodec` and `EchoCodec` for each variant
- `CommandCodec` that reads one byte, dispatches to the correct variant codec, and writes the type byte + variant on encode

```kotlin
val cmd: Command = CommandCodec.decode(buffer, DecodeContext.Empty)
CommandCodec.encode(outputBuffer, Command.Ping(timestampMillis), EncodeContext.Empty)
```

### `@DispatchOn` + `@DispatchValue` — Custom Discriminator Dispatch

Many protocols don't use a plain byte as their type discriminator. MQTT packs the packet type into the top 4 bits of a byte. PNG puts the chunk length *before* the chunk type. `@DispatchOn` handles these by letting you define a custom discriminator type.

**Step 1:** Define the discriminator as a `@ProtocolMessage` value class (or data class) with one `@DispatchValue` property that returns `Int`:

```kotlin
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue

@JvmInline
@ProtocolMessage
value class MqttFixedHeader(val raw: UByte) {
    @DispatchValue
    val packetType: Int get() = raw.toUInt().shr(4).toInt()        // top 4 bits — §2.2

    val flags: UByte get() = (raw.toUInt() and 0x0Fu).toUByte()    // bottom 4 bits

    // PUBLISH carries a packet identifier only when QoS > 0 (§3.3.2.2).
    val qosGreaterThanZero: Boolean get() = (raw.toUInt() and 0x06u) != 0u
}
```

**Step 2:** Annotate the sealed interface with `@DispatchOn` and give each variant a `@PacketType(value)` matching what `@DispatchValue` returns. Every MQTT variant carries the discriminator as its `header` field:

```kotlin
@DispatchOn(MqttFixedHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface MqttPacket<out P : Payload> {
    @PacketType(value = 2)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ConnAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x20u),
        val connectAckFlags: UByte,   // bit 0 = Session Present (§3.2.2.1)
        val returnCode: UByte,        // §3.2.2.3
    ) : MqttPacket<Nothing>

    @PacketType(value = 14)
    @ProtocolMessage
    data class Disconnect(
        val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
    ) : MqttPacket<Nothing>
}
```

The `out P : Payload` type parameter lets a variant carry a typed payload —
MQTT PUBLISH does; CONNACK and DISCONNECT don't, so they are `MqttPacket<Nothing>`
(see [Typed Payloads](#typed-payloads-and-the-payload-marker)). `@FramedBy` adds
the MQTT remaining-length framing — see [`@FramedBy`](#framedby--framework-owned-length-framing).

**Decode flow:** the dispatcher peeks the `MqttFixedHeader` byte without
consuming it, evaluates `@DispatchValue`, `when`-matches the result against each
`@PacketType(value)`, and delegates to that variant's codec — which reads the
header into its own `header` field. **Encode flow:** the variant's `header`
field is written directly, then its remaining fields.

Because each variant carries a field of the discriminator's type, no `wire`
byte needs to be declared. For a protocol whose variants do **not** carry the
discriminator, give `@PacketType` a `wire` argument so the framework knows the
raw bytes to write: `@PacketType(value = 1, wire = 0x10)`. The processor
validates at compile time that a `wire` value fits the discriminator's type —
`@PacketType(wire = 300)` against a `UByte` discriminator is a compile error.
Duplicate `value`s are rejected at compile time.

#### Multi-byte discriminators

The discriminator's inner type sets the wire width. HTTP/2 (RFC 7540 §4.1)
packs a 24-bit length and an 8-bit type into one 32-bit word — a `UInt`-backed
discriminator:

```kotlin
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Big)
value class Http2LengthAndType(val raw: UInt) {
    val length: Int get() = (raw shr 8).toInt()    // top 24 bits

    @DispatchValue
    val type: Int get() = (raw and 0xFFu).toInt()  // bottom 8 bits — RFC 7540 §11.2
}
```

A data-class discriminator (instead of a value class) handles formats that must
read several fields before the dispatch value is known.

### `@FramedBy` — Framework-Owned Length Framing

`@FramedBy` marks a `@ProtocolMessage` class (typically a sealed parent) with a
length prefix that is **computed from, and bounds, the body's wire size**. The
framework owns the framing: encode emits the prefix carrying the encoded body's
byte count; decode reads the prefix, narrows `buffer.limit()` to bound the
body, and asserts strict consumption.

```kotlin
import com.ditchoom.buffer.codec.annotations.FramedBy

@ProtocolMessage
@DispatchOn(MqttFixedHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
sealed interface MqttPacket<out P : Payload> {
    // Each variant carries the `header` field; the framework writes the MQTT
    // remaining-length variable-byte integer between `header` and the body,
    // computed from the encoded body's byte count (MQTT v3.1.1 §2.2.3).
}
```

The `codec` argument is a Kotlin `object` implementing
`BoundingLengthCodec<UInt>` — it drives the prefix wire format (variable-byte
integer, fixed width, …) and reports `maxWireSize`. The `after` argument names
the sibling field the prefix sits immediately after on the wire; the default
(empty) puts the prefix at offset 0. When applied to a sealed parent, every
variant inherits the framing rule — there is no per-variant override.

See [Length Codecs](#length-codecs-boundinglengthcodec) for the
`BoundingLengthCodec` contract.

### Value Classes — Zero-Overhead Typed Wrappers

Value classes wrapping a primitive type are supported as fields. The generated codec reads/writes the inner primitive directly with no boxing overhead:

```kotlin
@JvmInline
value class PacketId(val raw: UShort)

@JvmInline
value class Flags(val raw: UByte) {
    val sessionPresent: Boolean get() = raw.toInt() and 1 == 1
}

@ProtocolMessage
data class Acknowledgement(
    val flags: Flags,       // reads 1 byte, wraps in Flags
    val packetId: PacketId, // reads 2 bytes, wraps in PacketId
)
```

### List Fields

A `List<T>` field where `T` is a `@ProtocolMessage` data class is decoded as a
repeated sequence. The list's extent must be bounded — by `@RemainingBytes`, by
`@LengthFrom`, or by a `@LengthPrefixed` prefix driven by a `BoundingLengthCodec`:

```kotlin
// Bounded by the rest of the buffer
@ProtocolMessage
data class RepeatedBlocks(
    val streamId: UShort,
    @RemainingBytes val blocks: List<RepeatedBlock>,
)

// Bounded by a sibling byte-count field
@ProtocolMessage(wireOrder = Endianness.Big)
data class Http2SettingsFrame(
    @WireBytes(3) val length: UInt,
    val type: UByte,
    val flags: UByte,
    val streamId: Http2StreamId,
    @LengthFrom("length") val entries: List<Http2Setting>,
)

// Bounded by a variable-width length prefix
@ProtocolMessage
data class PropertyBagFrame(
    @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<PropertyEntry>,
)
```

### Typed Payloads and the `Payload` Marker

`Payload` is a marker interface for self-contained typed payload values. A
generic-over-payload codec bounds its type parameter with it (`<P : Payload>`),
and any concrete payload type implements it.

The processor enforces a **strict transitive shape rule**: it walks the
declared shape of every `Payload`-implementing type and rejects raw-bytes
members anywhere inside it — `ReadBuffer`, `WriteBuffer`, `PlatformBuffer`,
`ByteArray`, primitive arrays, `java.nio.ByteBuffer`. A `Payload` outlives the
codec's decode scope, and a raw-bytes member could reference reclaimed pool
memory. Decode into a self-contained value instead: a value class around a
scalar, `String`, or domain object; or a platform-native handle.

A typed payload field is decoded by a hand-written `Codec` referenced through
`@UseCodec`, with a length annotation supplying the body extent:

```kotlin
import com.ditchoom.buffer.codec.Payload

data class RemoteCommandPayload(
    val opcode: UInt,
    val parameters: String,
) : Payload

@ProtocolMessage(wireOrder = Endianness.Big)
data class RemoteCommand(
    @LengthPrefixed val id: String,
    @RemainingBytes @UseCodec(RemoteCommandPayloadCodec::class) val payload: RemoteCommandPayload,
)
```

To make a protocol generic over its payload, give a `@DispatchOn` sealed
interface an `out P : Payload` type parameter and let a variant carry `P`
directly in a `@RemainingBytes` (or length-annotated) field:

```kotlin
@DispatchOn(MqttFixedHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface MqttPacket<out P : Payload> {
    @PacketType(value = 3)
    @ProtocolMessage
    data class Publish<P : Payload>(
        val header: MqttFixedHeader = MqttFixedHeader(0x30u),
        @LengthPrefixed val topic: String,
        @When("header.qosGreaterThanZero") val packetId: PacketId? = null,  // PacketId wraps UShort
        @RemainingBytes val payload: P,
    ) : MqttPacket<P>
    // … payload-free variants are declared `: MqttPacket<Nothing>`
}
```

The generated codec for a payload-generic dispatcher is a **class**, not an
`object` — construct it with the payload's `Codec<P>`:

```kotlin
val codec = MqttPacketCodec(JpegImageCodec)   // JpegImageCodec : Codec<JpegImage>
val packet = codec.decode(buffer, DecodeContext.Empty)
```

For consumers who genuinely need raw bytes (IPC forwarding, persistence, debug
capture), step outside the `Payload` abstraction: decode into a non-`Payload`
type with a hand-written `Codec` that copies bytes out at the boundary — see
[Manual Codecs](#manual-codecs).

### Supported Types

| Kotlin Type | Default Wire Size | Signed |
|-------------|------------------|--------|
| `Byte` | 1 | Yes |
| `UByte` | 1 | No |
| `Short` | 2 | Yes |
| `UShort` | 2 | No |
| `Int` | 4 | Yes |
| `UInt` | 4 | No |
| `Long` | 8 | Yes |
| `ULong` | 8 | No |
| `Float` | 4 | — |
| `Double` | 8 | — |
| `Boolean` | 1 | — |
| `String` | Variable | — |

A `String` field always needs one of `@LengthPrefixed`, `@RemainingBytes`, or
`@LengthFrom` — a bare `String` has no wire extent. `String` bodies are UTF-8.
For other charsets, write a `Codec<String>` and attach it with `@UseCodec`; the
library ships `AsciiStringCodec` as a 7-bit-ASCII reference implementation.

## Stream Framing with `peekFrameSize`

The processor generates `peekFrameSize(stream, baseOffset): PeekResult` on
every codec whose frame size is determinable from the wire format. It inspects
a `StreamProcessor` to determine the total bytes the codec will read, without
consuming any data.

```kotlin
import com.ditchoom.buffer.codec.PeekResult

sealed interface PeekResult {
    value class Complete(val bytes: Int) : PeekResult  // a full frame is buffered
    data object NeedsMoreData : PeekResult             // wait for the next chunk
    data object NoFraming : PeekResult                 // this codec can't peek
}
```

This eliminates the manual offset math in streaming decode loops:

```kotlin
// Before: manual offset math, easy to get wrong
while (stream.available() >= 3) {
    val type = stream.peekByte(0)
    val length = stream.peekShort(1).toInt() and 0xFFFF  // hope the offset is right...
    val frameSize = 3 + length
    if (stream.available() < frameSize) break
    val msg = stream.readBufferScoped(frameSize) { PacketCodec.decode(this, DecodeContext.Empty) }
}

// After: generated, always correct
while (true) {
    when (val frame = PacketCodec.peekFrameSize(stream)) {
        is PeekResult.Complete -> {
            val msg = stream.readBufferScoped(frame.bytes) {
                PacketCodec.decode(this, DecodeContext.Empty)
            }
            handleMessage(msg)
        }
        PeekResult.NeedsMoreData -> break          // wait for more bytes
        PeekResult.NoFraming -> error("PacketCodec does not support stream framing")
    }
}
```

A codec that does not participate in framing returns `NoFraming` — the default.
This makes a codec used at a streaming boundary fail loudly instead of silently
hanging the receive loop.

**What the generator handles** (the codec emits a real `peekFrameSize`):

- Fixed-size fields — accumulated into constant offsets
- `@LengthPrefixed` — peeks the prefix (1/2/4 bytes), always treated as unsigned
- `@LengthFrom("field")` — peeks the referenced field's value at its known offset
- `@When` conditions — peeks the boolean (or value-class property) and branches
- `@WireBytes` custom widths — byte-by-byte assembly
- `@UseCodec` with a length annotation — bounded the same as the length type
- Multiple variable-length fields — sequential peek with dynamic offset tracking
- Sealed dispatch — peeks the discriminator, branches per variant, delegates
- `@DispatchOn` — peeks the value-class or data-class discriminator
- `@FramedBy` — peeks the framing length prefix
- Nested `@ProtocolMessage` — delegates to the nested codec's `peekFrameSize`

**When generation is skipped** (the codec compiles, `peekFrameSize` returns `NoFraming`):

- `@RemainingBytes` at top level — no length on the wire
- `@UseCodec` without a length annotation, or delegating to a hand-written codec
  that itself returns `NoFraming`

## The Codec Interface

`Codec<T>` is the union of three smaller interfaces. Send-only consumers can
depend on just `Encoder<T>`; receive-only consumers on just `Decoder<T>`.
Generated and hand-written codecs implement the same interfaces and compose
freely.

```kotlin
interface Encoder<in T> {
    fun encode(buffer: WriteBuffer, value: T, context: EncodeContext)
    fun wireSize(value: T, context: EncodeContext): WireSize = WireSize.BackPatch
}

interface Decoder<out T> {
    fun decode(buffer: ReadBuffer, context: DecodeContext): T
}

interface FrameDetector {
    fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.NoFraming
}

interface Codec<T> : Encoder<T>, Decoder<T>, FrameDetector
```

`wireSize` reports the on-wire byte count so the framework can pre-allocate:

```kotlin
sealed interface WireSize {
    value class Exact(val bytes: Int) : WireSize  // size known up front
    data object BackPatch : WireSize              // variable — framework grows + back-patches
}
```

Return `WireSize.Exact(n)` from a fixed-size codec. Variable-length codecs
(UTF-8 strings, sealed dispatch with variable variants, generic payloads)
return `WireSize.BackPatch`: the framework encodes into a `GrowableWriteBuffer`
and patches any preceding length prefix once the body size is known.

A `Decoder` must not retain the buffer past `decode` — the framework releases
the slice on return. To produce a value that outlives the decode scope, copy
bytes out at the boundary (`ReadBuffer.copyToByteArray`, a consumer-owned
`PlatformBuffer`, or a platform-native handle).

## CodecContext — Runtime Configuration

Pass typed configuration through the entire codec chain without global state — allocator hints, size limits, protocol-version pins, and so on.

```kotlin
interface CodecContext                       // marker
interface DecodeContext : CodecContext       // passed to decode
interface EncodeContext : CodecContext       // passed to encode
```

### Define Typed Keys

Keys are interfaces parameterized by their value type. Direction is encoded in the key, and keys are declared as Kotlin `object`s (compared by identity):

```kotlin
import com.ditchoom.buffer.codec.DecodeKey
import com.ditchoom.buffer.codec.EncodeKey
import com.ditchoom.buffer.codec.CodecKey

object MaxFrameBytesKey : DecodeKey<Int>      // visible only to DecodeContext
object StrictModeKey : EncodeKey<Boolean>     // visible only to EncodeContext
object ProtocolVersionKey : CodecKey<Int>     // visible in both directions
```

A `DecodeKey` is readable only from a `DecodeContext`, an `EncodeKey` only from
an `EncodeContext`, and a `CodecKey` from both. The `buffer-codec` module ships
one such key, `BufferFactoryKey : CodecKey<BufferFactory>`, for codecs that
allocate consumer-owned buffers.

### Read a Key Inside a Codec

```kotlin
object FrameCodec : Codec<Frame> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): Frame {
        val maxBytes = context[MaxFrameBytesKey] ?: Int.MAX_VALUE
        if (buffer.remaining() > maxBytes) {
            throw DecodeException(
                fieldPath = "Frame",
                bufferPosition = buffer.position(),
                expected = "<= $maxBytes bytes",
                actual = "${buffer.remaining()} bytes",
            )
        }
        // … decode the frame …
    }

    override fun encode(buffer: WriteBuffer, value: Frame, context: EncodeContext) { /* … */ }
}
```

`context[key]` returns `T?` — `null` when the key is absent. Apply a default at
the call site with `?:`.

### Pass Context at the Call Site

Contexts are immutable; `with` returns a new context:

```kotlin
val ctx = DecodeContext.Empty
    .with(MaxFrameBytesKey, 1_048_576)
    .with(ProtocolVersionKey, 5)

val result = TopLevelCodec.decode(buffer, ctx)
```

### How Context Flows

Generated code forwards the context automatically:

- **Sealed dispatch codecs** forward the context to every variant codec
- **`@UseCodec` fields** forward it to the referenced codec
- **Nested `@ProtocolMessage` fields** forward it to the nested codec

A codec that doesn't read from the context simply ignores it.

## Manual Codecs

When you need full control — a custom encoding, a protocol-level optimization, or a format that doesn't map to annotations — implement `Codec<T>` directly.

### Simple Example

```kotlin
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.*

data class SensorReading(val sensorId: UShort, val temperature: Int)

object SensorReadingCodec : Codec<SensorReading> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext) =
        SensorReading(buffer.readUShort(), buffer.readInt())

    override fun encode(buffer: WriteBuffer, value: SensorReading, context: EncodeContext) {
        buffer.writeUShort(value.sensorId)
        buffer.writeInt(value.temperature)
    }

    override fun wireSize(value: SensorReading, context: EncodeContext) = WireSize.Exact(6)
}
```

### A String Codec — `AsciiStringCodec`

The library's built-in `AsciiStringCodec` is the canonical template for a
hand-written codec. It is a 7-bit-ASCII `Codec<String>` you can attach with
`@UseCodec`, and a model for per-charset codecs of your own:

```kotlin
object AsciiStringCodec : Codec<String> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): String =
        buffer.readString(buffer.remaining(), Charset.UTF8)

    override fun encode(buffer: WriteBuffer, value: String, context: EncodeContext) {
        for (i in value.indices) {
            val code = value[i].code
            if (code > 0x7F) {
                throw EncodeException(
                    fieldPath = "AsciiStringCodec",
                    reason = "non-ASCII character at index $i",
                )
            }
        }
        buffer.writeString(value, Charset.UTF8)
    }

    override fun wireSize(value: String, context: EncodeContext): WireSize =
        WireSize.Exact(value.length)

    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
        PeekResult.NoFraming
}
```

### Length Codecs (`BoundingLengthCodec`)

`@FramedBy` and the `@LengthPrefixed @UseCodec` list shape need a codec that not
only encodes/decodes a length value but also **bounds the buffer** by it.
`BoundingLengthCodec<T>` extends `Codec<T>` with two extra members:

```kotlin
interface BoundingLengthCodec<T : Any> : Codec<T> {
    val maxWireSize: Int                                  // widest the prefix can be
    fun applyBound(buffer: ReadBuffer, decodedValue: T)   // narrow limit() by the decoded length
}
```

An MQTT remaining-length codec (variable-byte integer, 1–4 bytes) implements it like this:

```kotlin
object MqttRemainingLengthCodec : BoundingLengthCodec<UInt> {
    override val maxWireSize: Int = 4

    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt {
        var value = 0u
        var multiplier = 1u
        repeat(4) {
            val encoded = buffer.readUnsignedByte().toUInt()
            value += (encoded and 0x7Fu) * multiplier
            if ((encoded and 0x80u) == 0u) return value
            multiplier *= 128u
        }
        throw DecodeException(
            fieldPath = "MqttRemainingLength",
            bufferPosition = buffer.position(),
            expected = "continuation bit clear within 4 bytes",
            actual = "5th continuation byte",
        )
    }

    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
        var remaining = value
        do {
            var encodedByte = remaining and 0x7Fu
            remaining = remaining shr 7
            if (remaining > 0u) encodedByte = encodedByte or 0x80u
            buffer.writeByte(encodedByte.toByte())
        } while (remaining > 0u)
    }

    override fun wireSize(value: UInt, context: EncodeContext): WireSize =
        WireSize.Exact(
            when {
                value < 128u -> 1
                value < 16_384u -> 2
                value < 2_097_152u -> 3
                else -> 4
            },
        )

    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}
```

### Composing Codecs

Use one codec inside another for nested structures:

```kotlin
data class Envelope(val version: UByte, val sensor: SensorReading)

object EnvelopeCodec : Codec<Envelope> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext) = Envelope(
        version = buffer.readUByte(),
        sensor = SensorReadingCodec.decode(buffer, context),
    )

    override fun encode(buffer: WriteBuffer, value: Envelope, context: EncodeContext) {
        buffer.writeUByte(value.version)
        SensorReadingCodec.encode(buffer, value.sensor, context)
    }

    override fun wireSize(value: Envelope, context: EncodeContext): WireSize {
        val inner = SensorReadingCodec.wireSize(value.sensor, context)
        return if (inner is WireSize.Exact) WireSize.Exact(1 + inner.bytes) else WireSize.BackPatch
    }
}
```

### Manual Sealed Interface Dispatch

```kotlin
sealed interface Message
data class PingMessage(val timestamp: Long) : Message
data class DataMessage(val payload: Int) : Message

object MessageCodec : Codec<Message> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): Message =
        when (val type = buffer.readByte().toInt()) {
            0x01 -> PingMessage(buffer.readLong())
            0x02 -> DataMessage(buffer.readInt())
            else -> throw DecodeException(
                fieldPath = "Message.type",
                bufferPosition = buffer.position(),
                expected = "0x01 or 0x02",
                actual = "0x${type.toString(16)}",
            )
        }

    override fun encode(buffer: WriteBuffer, value: Message, context: EncodeContext) {
        when (value) {
            is PingMessage -> { buffer.writeByte(0x01); buffer.writeLong(value.timestamp) }
            is DataMessage -> { buffer.writeByte(0x02); buffer.writeInt(value.payload) }
        }
    }
}
```

Compare this to the `@PacketType` approach — the generated version eliminates the manual dispatch and catches duplicate type codes at compile time.

## Streaming Integration

For data that arrives in chunks, use `StreamProcessor` to accumulate bytes, then decode with any codec:

```kotlin
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.stream.StreamProcessor

withPool { pool ->
    val processor = StreamProcessor.create(pool)
    try {
        for (chunk in networkChannel) {
            processor.append(chunk)

            while (true) {
                when (val frame = MessageCodec.peekFrameSize(processor)) {
                    is PeekResult.Complete -> {
                        val message = processor.readBufferScoped(frame.bytes) {
                            MessageCodec.decode(this, DecodeContext.Empty)
                        }
                        handleMessage(message)
                    }
                    PeekResult.NeedsMoreData -> break
                    PeekResult.NoFraming -> error("MessageCodec cannot frame a stream")
                }
            }
        }
    } finally {
        processor.release()
    }
}
```

### Handling Incomplete Data (Buffer Underflow)

Generated codecs trust that their input buffer contains a complete message —
they don't validate `remaining()` before every read. This is by design: the
**framing layer** owns the "do I have enough data?" question, not the codec.

`StreamProcessor` is the framing layer:

- **`available()`** — total buffered bytes, without consuming
- **`peekByte(offset)` / `peekInt(offset)`** — read a value without consuming it
- **`readBuffer(size)`** / **`readBufferScoped(size) { … }`** — hand a complete
  frame to a codec; `readBufferScoped` releases the frame buffer back to the
  pool when the block returns

`peekFrameSize` is the preferred gate (above). When you must frame by hand,
peek the length, then check `available()` before reading:

```kotlin
while (processor.available() >= 5) {  // 1 byte type + 4 byte length
    val length = processor.peekInt(1)
    val totalSize = 5 + length
    if (processor.available() < totalSize) break  // wait for more data

    val message = processor.readBufferScoped(totalSize) {
        MessageCodec.decode(this, DecodeContext.Empty)
    }
    handleMessage(message)
}
```

For protocols where you want automatic refilling (e.g., reading from a socket),
wrap a `SuspendingStreamProcessor` in `AutoFillingSuspendingStreamProcessor` —
it calls your refill callback whenever a read needs more bytes:

```kotlin
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor

val processor = AutoFillingSuspendingStreamProcessor(delegate) { p ->
    val chunk = socket.read() ?: throw EndOfStreamException()
    p.append(chunk)
}
// Reads auto-fill when they need more bytes.
val message = processor.readBufferScoped(frameSize) {
    MessageCodec.decode(this, DecodeContext.Empty)
}
```

## Real-World Example: Zero-Copy RIFF Image Decoder

This example decodes an image stored in a RIFF container — a custom `IMG ` form alongside RIFF's standard `WAVE` and `AVI ` forms — and renders the bitmap in Jetpack Compose without copying pixel data during parsing. RIFF is little-endian; its 4-byte FourCC tags are read big-endian so each tag's bytes stay in file order.

### Wire Format

```
┌────────────────────────────┐
│ FileHeader    (12 bytes)   │  magic("RIFF") + fileSize + format("IMG ")
├────────────────────────────┤
│ ChunkHeader   (8 bytes)    │  chunkId("meta") + chunkSize
│ ImageMetadata (variable)   │  width, height, depth, title
├────────────────────────────┤
│ ChunkHeader   (8 bytes)    │  chunkId("pxls") + chunkSize
│ Raw pixel data             │  ← zero-copy slice, never copied during parse
└────────────────────────────┘
```

### Define the Codec Types

Use `@ProtocolMessage` for all structured headers. The binary pixel payload is **not** a codec field — it's extracted manually with `readBytes()` for zero-copy.

```kotlin
@ProtocolMessage(wireOrder = Endianness.Little)
data class FileHeader(
    @WireOrder(Endianness.Big) val magic: Int,    // "RIFF" = 0x52494646
    val fileSize: UInt,                           // little-endian, per the RIFF spec
    @WireOrder(Endianness.Big) val format: Int,   // "IMG " = 0x494D4720
)

@ProtocolMessage(wireOrder = Endianness.Little)
data class ChunkHeader(
    @WireOrder(Endianness.Big) val chunkId: Int,  // 4-byte ASCII chunk ID
    val chunkSize: UInt,                          // little-endian byte count
)

@ProtocolMessage(wireOrder = Endianness.Little)
data class ImageMetadata(
    val width: UInt,
    val height: UInt,
    val colorDepth: UShort,              // bits per pixel (32 = RGBA_8888)
    @LengthPrefixed val title: String,
)
```

:::tip Why not put pixel data in the codec?
A `@ProtocolMessage` field must be a primitive, a `String`, or a codec-composed
type — not a raw byte blob. For binary payloads:

- **Hybrid (shown here)**: a codec for the headers, manual `readBytes()` for the
  blob — the simplest approach
- **Hand-written `Codec` via `@UseCodec`**: write a `Codec<YourBlobType>` and
  attach it with `@RemainingBytes @UseCodec(...)` — fully codec-managed and
  reusable across protocols (see [`@UseCodec`](#usecodeccodec--delegate-to-a-hand-written-codec))
- **A `Payload`-marked type**: for payloads that carry their own structure — see
  [Typed Payloads](#typed-payloads-and-the-payload-marker)
:::

### Decode with Zero-Copy Pixel Extraction

```kotlin
data class RiffImage(
    val metadata: ImageMetadata,
    val pixelData: ReadBuffer,  // zero-copy slice of the original buffer
)

object RiffImageDecoder {
    private const val MAGIC_RIFF = 0x52494646  // "RIFF"
    private const val FORMAT_IMG = 0x494D4720  // "IMG "
    private const val CHUNK_META = 0x6D657461  // "meta"
    private const val CHUNK_PXLS = 0x70786C73  // "pxls"

    fun decode(buffer: ReadBuffer): RiffImage {
        // Generated codecs parse the structured headers — type-safe, field-order-safe.
        val header = FileHeaderCodec.decode(buffer, DecodeContext.Empty)
        require(header.magic == MAGIC_RIFF && header.format == FORMAT_IMG)

        val metaChunk = ChunkHeaderCodec.decode(buffer, DecodeContext.Empty)
        require(metaChunk.chunkId == CHUNK_META)
        val metadata = ImageMetadataCodec.decode(buffer, DecodeContext.Empty)

        val pxlsChunk = ChunkHeaderCodec.decode(buffer, DecodeContext.Empty)
        require(pxlsChunk.chunkId == CHUNK_PXLS)

        // Zero-copy: readBytes() returns a slice backed by the same memory.
        val pixelData = buffer.readBytes(pxlsChunk.chunkSize.toInt())

        return RiffImage(metadata, pixelData)
    }
}
```

**What's zero-copy here:** `readBytes()` returns a `ReadBuffer` slice that shares the same underlying native memory as the original buffer. No pixel data is copied during parsing. The headers are tiny (28 bytes total) and parsed by the generated codec — only the multi-megabyte pixel payload gets the zero-copy treatment.

### Render in Compose Multiplatform (Skia)

Use `nativeAddress` to create a Skia `Pixmap` that wraps the buffer's memory directly — no copy:

```kotlin
@Composable
fun RiffBitmap(buffer: ReadBuffer, modifier: Modifier = Modifier) {
    // Hold a reference to the decoded image so the backing buffer stays alive.
    val (imageBitmap, imageRef) = remember(buffer) {
        val img = RiffImageDecoder.decode(buffer)
        val w = img.metadata.width.toInt()
        val h = img.metadata.height.toInt()

        val nma = img.pixelData.nativeMemoryAccess
            ?: error("Use BufferFactory.Default for zero-copy Skia rendering")

        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        // Zero-copy: Pixmap wraps the buffer's native memory directly.
        val pixmap = Pixmap(info, nma.nativeAddress, info.minRowBytes)
        Image.makeFromPixmap(pixmap).toComposeImageBitmap() to img
    }
    Image(bitmap = imageBitmap, contentDescription = null, modifier = modifier)
}
```

:::warning Buffer Lifetime
`Pixmap` does not own the memory — it borrows the buffer's native address. The original buffer (or pool) must stay alive while the image is in use. In the example above, `imageRef` keeps the `RiffImage` (and its backing `ReadBuffer` slice) alive alongside the `ImageBitmap` inside `remember`.
:::

### Writing the Container

Codecs with a variable-length field (here, `ImageMetadata`'s `@LengthPrefixed`
title) report `WireSize.BackPatch` from `wireSize` — their size isn't known up
front. Encode the metadata into a scratch buffer first and measure its
`position()`:

```kotlin
fun encodeRiffImage(metadata: ImageMetadata, pixels: ReadBuffer): PlatformBuffer {
    // Encode the variable-length metadata once to learn its byte count.
    val metaScratch = BufferFactory.Default.allocate(256)
    ImageMetadataCodec.encode(metaScratch, metadata, EncodeContext.Empty)
    val metadataSize = metaScratch.position()
    metaScratch.resetForRead()

    val pixelSize = pixels.remaining()
    val totalSize = 4 + 8 + metadataSize + 8 + pixelSize  // format + 2 chunk headers + data

    val buffer = BufferFactory.Default.allocate(12 + totalSize)

    // File header
    FileHeaderCodec.encode(buffer, FileHeader(
        magic = 0x52494646,
        fileSize = totalSize.toUInt(),
        format = 0x494D4720,
    ), EncodeContext.Empty)

    // Metadata chunk
    ChunkHeaderCodec.encode(buffer, ChunkHeader(0x6D657461, metadataSize.toUInt()), EncodeContext.Empty)
    buffer.write(metaScratch)

    // Pixel data chunk — bulk copy from the source buffer
    ChunkHeaderCodec.encode(buffer, ChunkHeader(0x70786C73, pixelSize.toUInt()), EncodeContext.Empty)
    buffer.write(pixels)

    buffer.resetForRead()
    return buffer
}
```

### Copy Budget

| Step | Skia (`Pixmap`) | Naive (`ByteArray`) |
|------|-----------------|---------------------|
| Parse headers (codec) | Zero-copy | Zero-copy |
| Extract pixel data (`readBytes`) | Zero-copy (slice) | 1 copy (`copyToByteArray`) |
| Render | Zero-copy (`Pixmap` borrows memory) | 1 copy (`wrap` + `copyPixels`) |
| **Intermediate copies** | **0** | **2** |

The Skia path achieves **zero intermediate copies** of pixel data — the decoded pixels exist in exactly one place, the Skia-wrapped native buffer.
