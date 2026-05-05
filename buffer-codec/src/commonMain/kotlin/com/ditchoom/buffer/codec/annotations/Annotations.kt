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
 * Marks a String field to consume all remaining bytes as UTF-8.
 * Must be the last non-conditional field in the constructor.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class LogEntry(
 *     val level: UByte,
 *     @RemainingBytes val message: String,  // reads everything after level byte
 * )
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class RemainingBytes

/**
 * Marks a field whose byte length is determined by a **non-adjacent**
 * numeric field elsewhere in the message — the length is carried as a
 * separate constructor parameter because the consumer cares about it as a
 * number (e.g., it is parsed by a different codec, or several positions
 * ahead of the bounded field).
 *
 * **For adjacent length carriers, use `@LengthPrefixed` instead.** A field
 * whose only purpose is to bound the immediately following sibling is a
 * redundant length carrier — modeling it as an independent constructor
 * parameter encodes the same quantity twice (prefix vs. value's `wireSize`)
 * and is rejected by the validator (Phase 10 rule R1). The remaining valid
 * use of `@LengthFrom` is genuine remote-prefix: length carried in a non-
 * adjacent field, often parsed by a different codec or sitting several
 * positions away (MQTT-style header-bounded payloads, parent-passed
 * bounds via `@DispatchOn`, etc.).
 *
 * ```kotlin
 * @ProtocolMessage
 * data class RemoteHeader(
 *     val payloadLength: UShort,    // consumer-visible — flow control, routing
 *     val flags: UByte,
 *     val correlationId: UInt,
 *     @LengthFrom("payloadLength") val payload: String,
 * )
 * ```
 *
 * @param field The name of the non-adjacent numeric field that holds the
 * byte length. Must exist, come before this field, and be a numeric type.
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
 * Delegates field decoding/encoding to an existing [Codec][com.ditchoom.buffer.codec.Codec] object.
 *
 * Use this instead of writing a full SPI module when you need a custom field type.
 * The referenced [codec] must be a Kotlin `object` implementing `Codec<T>`.
 *
 * **Without a length annotation** — the codec reads directly from the buffer:
 * ```kotlin
 * @ProtocolMessage
 * data class Message(
 *     @UseCodec(VariableIntCodec::class) val length: Int,
 * )
 * // Generated: val length = VariableIntCodec.decode(buffer)
 * ```
 *
 * **With a length annotation** — the codec receives a size-limited slice:
 * ```kotlin
 * @ProtocolMessage
 * data class ImageFrame(
 *     val bitmapLength: Int,
 *     @UseCodec(PngBitmapCodec::class) @LengthFrom("bitmapLength") val bitmap: ImageBitmap,
 * )
 * // Generated: val _slice = buffer.readBytes(bitmapLength); val bitmap = PngBitmapCodec.decode(_slice)
 * ```
 *
 * Composes with [@LengthPrefixed], [@RemainingBytes], and [@LengthFrom].
 *
 * @param codec A `KClass` referencing a Kotlin `object` that implements `Codec<T>`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class UseCodec(
    val codec: kotlin.reflect.KClass<*>,
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
