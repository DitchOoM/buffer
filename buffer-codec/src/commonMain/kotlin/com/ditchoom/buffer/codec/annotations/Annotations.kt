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
 * // Generates SensorReadingCodec with decode() and encode()
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
    /**
     * Controls whether the generated codec supports encode, decode, or both.
     *
     * - [Direction.Infer] (default): automatically inferred from fields.
     *   If any field uses a decode-only `@UseCodec`, the codec becomes decode-only.
     * - [Direction.Codec]: asserts bidirectional — compile error if any field is unidirectional.
     * - [Direction.DecodeOnly]: generates only `decode()` — compile error if any field is encode-only.
     * - [Direction.EncodeOnly]: generates only `encode()` — compile error if any field is decode-only.
     */
    val direction: Direction = Direction.Infer,
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
 * Marks a type parameter as the application payload.
 * The generated codec will provide a scoped `PayloadReader` for decoding.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class Packet<@Payload P>(
 *     val version: UByte,
 *     @LengthPrefixed val payload: P,
 * )
 * // Generates PacketCodec with a PayloadReader context for decoding P
 * ```
 */
@Target(AnnotationTarget.TYPE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Payload

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
 * Marks a String or payload field as length-prefixed: prefix bytes followed by UTF-8 data.
 * Default is 2-byte big-endian (`UShort`) prefix.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class GreetingMessage(
 *     @LengthPrefixed val name: String,                         // 2-byte prefix (default)
 *     @LengthPrefixed(LengthPrefix.Byte) val nickname: String,  // 1-byte prefix (max 255)
 *     @LengthPrefixed(LengthPrefix.Int) val bio: String,        // 4-byte prefix
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
 * Marks a String field whose byte length is determined by a preceding numeric field.
 * The referenced field must exist, come before this field, and be a numeric type.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class NamedRecord(
 *     val nameLength: UShort,
 *     @LengthFrom("nameLength") val name: String,  // reads nameLength bytes as UTF-8
 *     val value: Int,
 * )
 * ```
 *
 * @param field The name of the preceding numeric field that holds the byte length.
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
 * Controls whether a `@ProtocolMessage` generates encode, decode, or both.
 *
 * Used with [ProtocolMessage.direction] to explicitly control or assert the
 * directionality of the generated codec. Direction is inferred from fields by default:
 * if any field uses a decode-only `@UseCodec` (one that only implements `Decoder<T>`),
 * the generated codec becomes decode-only automatically.
 */
enum class Direction {
    /** Infer from fields: decode-only if any field is decode-only, etc. (default, non-breaking). */
    Infer,

    /** Assert bidirectional — compile error if any field is unidirectional. */
    Codec,

    /** Force decode-only — compile error if any field is encode-only. */
    DecodeOnly,

    /** Force encode-only — compile error if any field is decode-only. */
    EncodeOnly,
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
 * Conditional field: only present on the wire when the referenced expression is `true`.
 * The field must be nullable with a default value of `null`.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class OptionalPayload(
 *     val hasExtra: Boolean,
 *     @WhenTrue("hasExtra") val extra: Int? = null,  // only read/written when hasExtra == true
 * )
 * ```
 *
 * Dotted expressions access properties on value class fields:
 *
 * ```kotlin
 * @WhenTrue("flags.willFlag") val willTopic: String? = null
 * ```
 *
 * @param expression `"fieldName"` for a Boolean field, or `"fieldName.property"` for a
 *   property on a value class field.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class WhenTrue(
    val expression: String,
)

/**
 * Conditional field: only present on the wire when at least [minBytes] remain
 * in the buffer after reading all preceding fields.
 *
 * The field must be nullable with a default value of `null`.
 * All `@WhenRemaining` fields must be contiguous and at the tail of the constructor.
 *
 * On encode, fields are written with cascading null checks: if an earlier
 * `@WhenRemaining` field is null, all subsequent ones are skipped — preventing
 * an impossible wire state where a trailing field is present without its predecessor.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class AckV5(
 *     val packetId: UShort,
 *     @WhenRemaining(1) val reasonCode: UByte? = null,
 *     @WhenRemaining(1) val properties: Collection<Property>? = null,
 * )
 * ```
 *
 * @param minBytes Minimum bytes remaining in buffer for this field to be present.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class WhenRemaining(
    val minBytes: Int,
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
