package com.ditchoom.buffer.codec.annotations

/**
 * Marks a data class or sealed interface as a protocol message.
 * KSP will generate a `Codec` implementation for this type at compile time.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class SensorReading(
 *     val sensorId: UShort,  // 2 bytes
 *     val temperature: Int,  // 4 bytes
 * )
 * // Generates SensorReadingCodec with decode(), encode(), and sizeOf()
 * ```
 *
 * **Note:** Generated code appears after compilation (`./gradlew build`).
 * IDE features (autocomplete, navigation) for generated codecs require an initial build.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ProtocolMessage(
    /**
     * Wire byte order for all multi-byte numeric fields in this message.
     * Defaults to [Endianness.Default] (use the buffer's byte order).
     *
     * On a sealed interface, variants inherit this value unless they override it.
     * Individual fields can further override with [WireOrder].
     */
    val wireOrder: Endianness = Endianness.Default,
)

/**
 * Specifies the discriminator value for a variant of a `@ProtocolMessage` sealed interface.
 *
 * The generated dispatch codec reads one byte, matches it against each variant's [value],
 * and delegates to the correct variant codec. On encode, [wire] is written as the type byte.
 *
 * **Simple dispatch** (no `@DispatchOn`): [value] is both the match value and the wire byte.
 * ```kotlin
 * @ProtocolMessage
 * sealed interface Command {
 *     @ProtocolMessage @PacketType(0x01)
 *     data class Ping(val timestamp: Long) : Command
 *
 *     @ProtocolMessage @PacketType(0x02)
 *     data class Echo(@LengthPrefixed val message: String) : Command
 * }
 * ```
 *
 * **Bit-packed dispatch** (with `@DispatchOn`): [value] is the extracted dispatch value,
 * [wire] is the raw byte written on encode.
 * ```kotlin
 * @DispatchOn(MqttFixedHeader::class)
 * sealed interface MqttPacket {
 *     @PacketType(value = 1, wire = 0x10)  // type=1, raw byte=0x10
 *     data class Connect(...) : MqttPacket
 *
 *     @PacketType(value = 6, wire = 0x62)  // type=6, raw byte=0x62 (flags=0x02)
 *     data class PubRel(...) : MqttPacket
 * }
 * ```
 *
 * @param value The discriminator value to match during decode (0-255).
 * @param wire The raw byte to write during encode. Defaults to -1, meaning use [value].
 *   Required when `@DispatchOn` extracts a different value than the raw wire byte.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PacketType(
    val value: Int,
    val wire: Int = -1,
)

/**
 * Length prefix encoding for [LengthPrefixed] fields.
 * All variants use big-endian (network) byte order.
 */
enum class LengthPrefix {
    /** 1 byte unsigned (max 255) */
    Byte,

    /** 2 bytes big-endian (default) */
    Short,

    /** 4 bytes big-endian */
    Int,
}

/**
 * Marks a length-prefixed field: prefix bytes carrying the value's wire size,
 * followed by the value's bytes. Default prefix width is 2-byte big-endian
 * (`UShort`).
 *
 * Accepted on:
 *   - `String` fields — the value is the field's UTF-8 bytes.
 *   - `@ProtocolMessage` data class fields — the value is the message body's
 *     wire bytes; encode emits the prefix carrying the body's `wireSize`,
 *     decode reads the prefix, bounds inner decode, restores the outer limit.
 *
 * For adjacent length carriers, prefer `@LengthPrefixed` over modeling the
 * length as a separate constructor parameter and a `@LengthFrom` reference —
 * a redundant length carrier is an impossible-state class (the prefix and
 * `body.wireSize()` independently encode the same quantity). `@LengthFrom`
 * is reserved for genuine remote-prefix uses (length carried in a non-
 * adjacent field).
 *
 * ```kotlin
 * @ProtocolMessage
 * data class GreetingMessage(
 *     @LengthPrefixed val name: String,                         // 2-byte prefix (default)
 *     @LengthPrefixed(LengthPrefix.Byte) val nickname: String,  // 1-byte prefix (max 255)
 *     @LengthPrefixed(LengthPrefix.Int) val bio: String,        // 4-byte prefix
 * )
 *
 * @ProtocolMessage
 * data class WavFmtBody(val audioFormat: UShort, /* ... */)
 *
 * @ProtocolMessage(wireOrder = Endianness.Little)
 * data class WavFmtChunk(
 *     val fourCC: UInt,
 *     @LengthPrefixed(LengthPrefix.Int) val body: WavFmtBody,   // 4-byte LE prefix carries body.wireSize
 * )
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class LengthPrefixed(
    val prefix: LengthPrefix = LengthPrefix.Short,
)

/**
 * Marks a field that consumes the remaining bytes of the bounded buffer.
 *
 * Accepted on:
 *   - `String` — UTF-8 body consuming the rest of the buffer.
 *   - `List<T>` where `T` is a `@ProtocolMessage` data class — loop
 *     reads nested-message bodies until the bound is reached.
 *   - Typed binary payload via `@RemainingBytes @UseCodec(C::class) val: P`
 *     — the user codec consumes the bounded region in one call. Use this
 *     instead of a raw scalar list when the bytes are opaque (an image,
 *     a compressed blob, an encrypted payload). See [UseCodec].
 *
 * For protocols that genuinely need a typed list of single bytes the
 * scalar-list shape (`List<UByte>` / `List<Byte>`) is also accepted, but
 * the typed-payload pattern above is the preferred way to model a binary
 * blob.
 *
 * ## Bounding the body
 *
 * The decoder reads against `buffer.limit()`. Callers (or an outer
 * codec) are responsible for narrowing `buffer.limit()` to the body's
 * extent before invoking decode — typical for protocols whose framing
 * carries the body byte count outside the codec's view (MQTT's
 * fixed-header remaining-length variable-byte-integer, parsed by an
 * outer dispatcher; HTTP/2 payload length, parsed by the frame
 * header).
 *
 * ## Simplest case (terminal)
 *
 * ```kotlin
 * @ProtocolMessage
 * data class LogEntry(
 *     val level: UByte,
 *     @RemainingBytes val message: String,  // reads everything after level byte
 * )
 * ```
 *
 * ## Trailing FixedSize fields
 *
 * `@RemainingBytes` may appear before the last constructor parameter,
 * provided every trailing field is fixed-size on the wire (a plain
 * scalar or a value-class scalar). The decoder subtracts the trailers'
 * summed wire bytes from `buffer.limit()` before the body read so the
 * trailers survive intact:
 *
 * ```kotlin
 * @ProtocolMessage
 * data class TextWithChecksum(
 *     val tag: UByte,
 *     @RemainingBytes val text: String,   // bounded to limit() - 4 (the crc)
 *     val crc: UInt,                       // 4-byte FixedSize trailer
 * )
 * ```
 *
 * Variable-size trailers (`@LengthPrefixed`, `@LengthFrom`, another
 * `@RemainingBytes`, `@When`, `@UseCodec`) are rejected by the
 * validator with a focused error: the body decode would have no way
 * to know its end without re-encoding. Move `@RemainingBytes` to the
 * end of the constructor parameter list, or remove the trailer.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class RemainingBytes

/**
 * Marks a field whose byte length is determined by a numeric sibling
 * elsewhere in the message — the length is carried as a separate
 * constructor parameter because the consumer cares about it as a number
 * (flow control, routing) or because the on-wire prefix shape is one
 * `@LengthPrefixed` cannot express (e.g. TLS uint24).
 *
 * Accepted on:
 *   - `String` fields — body is a single UTF-8 string sized by the sibling.
 *   - `List<T>` where `T` is a `@ProtocolMessage` data class — body is a
 *     sequence of nested-message bodies, byte-bounded by the sibling.
 *   - `T` where `T` is a `@ProtocolMessage` data class or sealed parent —
 *     body is a single nested message; the sibling-derived length covers
 *     the whole nested wire form. Decode narrows `buffer.limit()` to the
 *     bounded extent and delegates to `<TCodec>.decode`; encode delegates
 *     to `<TCodec>.encode` and the user is responsible for sizing the
 *     sibling to the body's wire byte count (row 16 trust contract).
 *
 * ## When to prefer @LengthPrefixed
 *
 * **For adjacent length carriers, prefer `@LengthPrefixed`** when the
 * prefix shape matches one of [LengthPrefix.Byte] / [LengthPrefix.Short] /
 * [LengthPrefix.Int]. A field whose only purpose is to bound the
 * immediately following sibling is a redundant length carrier — modeling
 * it as an independent constructor parameter encodes the same quantity
 * twice (prefix vs. value's `wireSize`). The validator (Phase 10 rule
 * R1) rejects the redundant shape for `String` and `List<T>` bodies.
 *
 * ## When @LengthFrom is the only option
 *
 * Adjacent siblings remain valid for nested `@ProtocolMessage` bodies
 * because `@LengthPrefixed` only supports 1 / 2 / 4-byte prefixes —
 * protocols with non-standard prefix widths cannot express their wire
 * shape via `@LengthPrefixed`. Example: TLS 1.3 handshake header (RFC
 * 8446 §4) uses a 3-byte big-endian length:
 *
 * ```kotlin
 * @ProtocolMessage(wireOrder = Endianness.Big)
 * data class TlsHandshake(
 *     val msgType: UByte,
 *     @WireBytes(3) val length: UInt,                // uint24
 *     @LengthFrom("length") val body: TlsHandshakeBody,
 * )
 * ```
 *
 * Genuine remote-prefix: length carried in a non-adjacent field, often
 * parsed by a different codec or sitting several positions away
 * (MQTT-style header-bounded payloads, parent-passed bounds via
 * `@DispatchOn`, etc.):
 *
 * ```kotlin
 * @ProtocolMessage
 * data class RemoteHeader(
 *     val payloadLength: UShort,     // consumer-visible — flow control, routing
 *     val flags: UByte,
 *     val correlationId: UInt,
 *     @LengthFrom("payloadLength") val payload: String,
 * )
 * ```
 *
 * @param field The name of the sibling field that holds the byte length.
 *   Must exist, come before this field, and resolve to either a
 *   non-nullable numeric scalar (`Byte`/`Short`/`Int`/`Long`/`UByte`/
 *   `UShort`/`UInt`/`ULong`) for the simple form `"siblingName"`, or a
 *   value class with a `val` property returning non-nullable `Int` for
 *   the dotted form `"siblingName.property"`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class LengthFrom(
    val field: String,
)

/**
 * Overrides the wire width of a numeric field. The [value] specifies the number
 * of bytes on the wire (1-8). Must not exceed the Kotlin type's natural size.
 * Cannot be used on `Float`, `Double`, or `Boolean`.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class CompactHeader(
 *     @WireBytes(3) val length: Int,    // 3 bytes on the wire, decoded into Int
 *     @WireBytes(6) val offset: Long,   // 6 bytes on the wire, decoded into Long
 * )
 * ```
 *
 * @param value Number of bytes on the wire (1-8).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class WireBytes(
    val value: Int,
)

/**
 * Wire byte order for [ProtocolMessage.wireOrder] and [WireOrder].
 */
enum class Endianness {
    /** Use the buffer's byte order (default — no swap). */
    Default,

    /** Big-endian (network byte order). */
    Big,

    /** Little-endian. */
    Little,
}

/**
 * Overrides the byte order for a single field, taking precedence over
 * [ProtocolMessage.wireOrder]. Use when a protocol mixes byte orders
 * within a single message (e.g., big-endian magic + little-endian lengths).
 *
 * ```kotlin
 * @ProtocolMessage(wireOrder = Endianness.Little)
 * data class MixedHeader(
 *     @WireOrder(Endianness.Big) val magic: UInt,  // overrides to big-endian
 *     val length: UInt,                              // inherits little-endian
 * )
 * ```
 *
 * @param order The byte order for this field on the wire.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class WireOrder(
    val order: Endianness,
)

/**
 * Conditional field: only present on the wire when the [predicate] holds.
 * The field must be nullable. Setting `= null` as the constructor default is conventional
 * (so the data class can be constructed without naming the field when the predicate
 * is false) but is **not** enforced — KSP cannot inspect default expression trees, so
 * any rule the validator can't actually check is not part of the contract
 * (Locked Decision row 19).
 *
 * ## Grammar
 *
 * The predicate language is **deliberately narrow**: the validator parses literal
 * forms only — no `&&` / `||`, no `!=`, no field-to-field comparisons, no method calls.
 * If a use case doesn't fit, model it with `@UseCodec` and a custom codec instead.
 * Two forms are accepted:
 *
 * ### 1. Dotted Boolean path on a prior sibling
 *
 * `"<siblingField>"` resolves to a sibling `Boolean` constructor parameter declared
 * before the bound parameter; `"<siblingField>.<property>"` resolves to a `Boolean`-
 * returning `val` on a sibling `@JvmInline value class`.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class OptionalPayload(
 *     val hasExtra: Boolean,
 *     @When("hasExtra") val extra: Int? = null,
 * )
 *
 * @When("flags.willFlag") val willTopic: String? = null
 * ```
 *
 * ### 2. `remaining <op> <int-literal>` *(reserved — not yet implemented)*
 *
 * `"remaining <op> <int>"` where `<op> ∈ {>=, >, ==}` gates the slot on the bounded
 * decode buffer's `remaining()`. The identifier `remaining` is reserved/magic and
 * does not refer to a sibling field. Designed as the future replacement for the
 * never-introduced `@WhenRemaining(N)` annotation; lands as part of the v5
 * property-list work (Phase J.M.5). Until then, this grammar is documented but
 * not parsed — using it today produces the standard "sibling not found" diagnostic.
 *
 * ## Compound conditions: use a value-class getter
 *
 * The grammar is intentionally minimal — no `&&` / `||` / cross-field comparisons.
 * For predicates that combine multiple flags, model the combined condition as a
 * `val` property on a sibling `@JvmInline value class` and reference it through
 * grammar 1's dotted form. The property is plain Kotlin: any expression that
 * returns `Boolean` is fair game (bit tests on the inner scalar, comparisons
 * against constants, two-flag conjunctions, …).
 *
 * Example — HTTP/2 DATA frame's pad-length field is present only when both
 * `PADDED` is set in the flags AND the payload has bytes left:
 *
 * ```kotlin
 * @JvmInline
 * @ProtocolMessage
 * value class Http2Flags(val raw: UByte) {
 *     val padded: Boolean get() = (raw.toInt() and 0x08) != 0
 *     val endStream: Boolean get() = (raw.toInt() and 0x01) != 0
 *     // Compound predicate composed in Kotlin, exposed as a single Boolean val:
 *     val paddedWithBody: Boolean get() = padded && (raw.toInt() and 0xF0) != 0
 * }
 *
 * @ProtocolMessage(wireOrder = Endianness.Big)
 * data class Http2DataFrame(
 *     val flags: Http2Flags,
 *     @When("flags.paddedWithBody") val padLength: UByte? = null,
 *     // … rest of the frame
 * )
 * ```
 *
 * The validator only sees `flags.paddedWithBody` returning `Boolean` — the
 * combined logic stays in code where it's testable and refactorable, and the
 * predicate language stays narrow enough to keep the validator's diagnostics
 * actionable.
 *
 * ## Semantics
 *
 * Encoder semantics (row 19): when the predicate is `false`, the entire slot is
 * skipped on the wire (zero bytes written, including any `@LengthPrefixed` prefix).
 * When the predicate is `true` and the field's value is `null`, encode throws
 * `EncodeException` with field-path attribution.
 *
 * @param predicate Grammar 1 (`"siblingField"` or `"siblingField.property"`) today;
 *   grammar 2 (`"remaining <op> <int>"`) reserved for Phase J.M.5.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class When(
    val predicate: String,
)

/**
 * Delegates field decoding/encoding to an existing [Codec][com.ditchoom.buffer.codec.Codec]
 * object.
 *
 * **Use this only for custom, hand-written codecs** — variable-byte-integer encoders,
 * image-bitmap parsers, var-byte-int reason-code encodings, and the like. If the field's
 * type is itself annotated with [ProtocolMessage], declare the field with that type
 * directly instead — the processor generates the codec by convention and wires it up
 * automatically, including sealed dispatch and forward references to codecs generated
 * in the same compilation round. `@UseCodec` cannot forward-reference a KSP-generated
 * codec class.
 *
 * The referenced [codec] must be a Kotlin `object` implementing `Codec<T>` (or, for
 * the bounding shape, [BoundingLengthCodec][com.ditchoom.buffer.codec.BoundingLengthCodec]).
 * Standalone `expect`/`actual` codecs are supported (Kotlin linker resolves the
 * platform-side actual at the call site).
 *
 * ## Bare scalar — codec reads directly from the buffer
 *
 * ```kotlin
 * @ProtocolMessage
 * data class Message(
 *     @UseCodec(VariableIntCodec::class) val length: Int,
 * )
 * // Generated: val length = VariableIntCodec.decode(buffer, context)
 * ```
 *
 * ## Length-prefixed payload — typed binary slot framed by an inline prefix
 *
 * ```kotlin
 * @ProtocolMessage
 * data class V5Properties(
 *     @LengthPrefixed @UseCodec(JpegBitmapCodec::class) val bitmap: ImageBitmap,
 * )
 * // Generated: read 2-byte UShort prefix, narrow buffer.limit() by the prefix value,
 * // run JpegBitmapCodec.decode(buffer, context), restore the outer limit on finally.
 * ```
 *
 * ## Length-prefixed list — codec is `BoundingLengthCodec<UInt>` driving a var-width prefix
 *
 * ```kotlin
 * @ProtocolMessage
 * data class V5Connect(
 *     @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<V5Property>,
 * )
 * // Generated: prefix codec writes a variable-byte-integer header carrying the
 * // body byte count; element loop runs against the bounded region.
 * ```
 *
 * ## Bounded by a sibling — `@LengthFrom` provides the byte count
 *
 * ```kotlin
 * @ProtocolMessage
 * data class ImageFrame(
 *     val bitmapLength: Int,
 *     @UseCodec(PngBitmapCodec::class) @LengthFrom("bitmapLength") val bitmap: ImageBitmap,
 * )
 * // Generated: narrow buffer.limit() by bitmapLength.toInt(), call PngBitmapCodec.decode,
 * // restore the outer limit on finally.
 * ```
 *
 * ## When NOT to use @UseCodec
 *
 * For nested `@ProtocolMessage` types, attach length annotations directly to the field
 * — `@UseCodec` is unnecessary and forward-reference-incompatible:
 *
 * ```kotlin
 * @ProtocolMessage
 * data class Body(@LengthPrefixed val name: String)
 *
 * @ProtocolMessage
 * data class Frame(
 *     val length: UShort,
 *     @LengthFrom("length") val body: Body,  // Body has @ProtocolMessage — no @UseCodec
 * )
 * ```
 *
 * Composes with [LengthPrefixed], [RemainingBytes], and [LengthFrom].
 *
 * @param codec A `KClass` referencing a Kotlin `object` that implements `Codec<T>`
 *   (or `BoundingLengthCodec<UInt>` when paired with `@LengthPrefixed` on a `List<T>`).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class UseCodec(
    val codec: kotlin.reflect.KClass<*>,
)

/**
 * Marks a `@ProtocolMessage` class (typically a sealed parent) with a framing
 * length prefix that is computed from — and bounds — the body's wire size.
 *
 * The framework owns framing: the encode path emits the prefix carrying the
 * encoded body's byte count, the decode path reads the prefix, narrows
 * `buffer.limit()` to bound the body, and asserts strict consumption.
 * Replaces the slice 14a `@DerivedLength` annotation, which closed the
 * caller/framework desync only on a fixed-suffix shape; `@FramedBy` is
 * structural and handles fixed-suffix and BackPatch-suffix bodies uniformly.
 *
 * Sealed-parent composition: when applied to a `@ProtocolMessage` sealed
 * parent, every variant inherits the framing rule. There is no per-variant
 * override — protocols whose framing varies by variant are out of scope for
 * this annotation. Validator enforces that every variant of a `@FramedBy`
 * parent has the named [after] field as a FixedSize discriminator.
 *
 * @param codec A `KClass` referencing a Kotlin `object` that implements
 *   `BoundingLengthCodec<UInt>`. The codec drives prefix wire format
 *   (var-byte-int, fixed-width, etc.) and provides `maxWireSize` so the
 *   emitter can size the slack region for the slicing scheme.
 * @param after Names a sibling constructor field that the prefix sits
 *   immediately *after* on the wire. Empty (default) means the prefix is at
 *   offset 0. The named field must exist, have Exact wire width, and — when
 *   the class carries `@PacketType` — be the discriminator.
 *
 * ```kotlin
 * @ProtocolMessage
 * @FramedBy(MqttRemainingLengthCodec::class, after = "header")
 * sealed interface MqttPacket {
 *     // Each variant declares its own header field; the framework writes
 *     // the prefix between header and body, computed from body bytes.
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class FramedBy(
    val codec: kotlin.reflect.KClass<out com.ditchoom.buffer.codec.BoundingLengthCodec<UInt>>,
    val after: String = "",
)

/**
 * Specifies a custom discriminator type for a `@ProtocolMessage` sealed interface.
 *
 * By default, `@PacketType` dispatch reads a single byte and matches its full value.
 * `@DispatchOn` overrides this: the processor reads the specified [type] first, then
 * dispatches on the property marked `@DispatchValue` within that type.
 *
 * This enables protocols with bit-packed headers where the discriminator is not
 * the full byte. The discriminator value is forwarded to sub-codecs via `CodecContext`,
 * so variants can access fields like flags without re-reading.
 *
 * ```kotlin
 * @JvmInline
 * @ProtocolMessage
 * value class MqttFixedHeader(val raw: UByte) {
 *     @DispatchValue
 *     val packetType: Int get() = raw.toUInt().shr(4).toInt()
 *     val flags: UByte get() = (raw and 0x0Fu)
 * }
 *
 * @DispatchOn(MqttFixedHeader::class)
 * sealed interface MqttControlPacket {
 *     @PacketType(1) @ProtocolMessage data class Connect(...) : MqttControlPacket
 *     @PacketType(3) @ProtocolMessage data class Publish(val header: MqttFixedHeader, ...) : MqttControlPacket
 *     @PacketType(12) object PingRequest : MqttControlPacket
 * }
 * ```
 *
 * @param type A `KClass` referencing a `@ProtocolMessage` type (typically a value class)
 *   that contains a `@DispatchValue`-annotated property.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class DispatchOn(
    val type: kotlin.reflect.KClass<*>,
)

/**
 * Marks a property as the dispatch value within a `@DispatchOn` discriminator type.
 *
 * The annotated property must return an `Int`. The generated dispatch codec reads the
 * discriminator type, evaluates this property, and uses the result to match against
 * `@PacketType` values.
 *
 * Exactly one property per class may be annotated with `@DispatchValue`.
 *
 * ```kotlin
 * @JvmInline
 * @ProtocolMessage
 * value class MqttFixedHeader(val raw: UByte) {
 *     @DispatchValue
 *     val packetType: Int get() = raw.toUInt().shr(4).toInt()
 *
 *     val flags: UByte get() = (raw and 0x0Fu)
 * }
 * ```
 *
 * @see DispatchOn
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class DispatchValue
