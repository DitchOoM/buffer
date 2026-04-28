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
     * **Deprecated.** Prefer the class-level [Decode] / [Encode] markers — the absence
     * of either marker means "infer from fields" (decode-only if any field is decode-only,
     * etc.), which is the default behavior. The `direction` parameter remains as a
     * one-cycle compatibility shim and may be removed in a later release.
     *
     * - [Direction.Default] (default): infer from fields (decode-only if any field is
     *   decode-only, etc.).
     * - [Direction.Codec]: assert bidirectional — compile error if any field is unidirectional.
     * - [Direction.DecodeOnly]: generate only `decode()` — compile error if any field is encode-only.
     * - [Direction.EncodeOnly]: generate only `encode()` — compile error if any field is decode-only.
     */
    val direction: Direction = Direction.Default,
    /**
     * Fully-qualified name of an exception class with a single-`String` constructor that the
     * generated sealed dispatcher throws when the wire discriminator matches no variant. Empty
     * string (default) means [IllegalArgumentException].
     *
     * Resolved and validated at KSP time:
     * - The FQN must resolve in the compilation classpath visible to this `@ProtocolMessage`.
     * - The class must declare a `(String)` constructor.
     *
     * Only meaningful on a sealed root carrying [PacketType] / [PacketTypeRange] variants;
     * ignored on leaf messages.
     */
    val onUnknownDiscriminator: String = "",
)

/**
 * Marks a `@ProtocolMessage` class as decode-only — the processor generates a `Decoder<T>`
 * but no `Encoder<T>`. Mutually exclusive with [Encode] (compile error if both are present).
 *
 * Replaces `@ProtocolMessage(direction = Direction.DecodeOnly)`. Absence of both markers
 * means direction is inferred from fields: a class with any decode-only `@UseCodec` becomes
 * decode-only automatically.
 *
 * ```kotlin
 * @ProtocolMessage
 * @Decode
 * data class IncomingFrame(@LengthPrefixed val payload: String)
 * // Generates IncomingFrameCodec with only decode()
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Decode

/**
 * Marks a `@ProtocolMessage` class as encode-only — the processor generates an `Encoder<T>`
 * but no `Decoder<T>`. Mutually exclusive with [Decode] (compile error if both are present).
 *
 * Replaces `@ProtocolMessage(direction = Direction.EncodeOnly)`. Absence of both markers
 * means direction is inferred from fields.
 *
 * ```kotlin
 * @ProtocolMessage
 * @Encode
 * data class OutgoingFrame(@LengthPrefixed val payload: String)
 * // Generates OutgoingFrameCodec with only encode()
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Encode

/**
 * Variant of a `@ProtocolMessage` sealed interface that claims a single discriminator value.
 *
 * - **No `@DispatchOn`**: matches when the raw discriminator byte equals [wire].
 * - **With `@DispatchOn`**: matches when the discriminator type's `@DispatchValue` extraction
 *   equals [wire]. Encoding still needs a wire byte; if the variant carries a
 *   `@DiscriminatorField` (e.g. an `MqttFixedHeader` value class encoding both type and flags),
 *   the dispatcher delegates discriminator-byte encoding to that field. Otherwise it writes
 *   [wire] as the discriminator byte.
 *
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
 * For dispatch where the type bits are the **high-order** bits and the variant claims a
 * contiguous range of wire bytes (e.g. MQTT PUBLISH 0x30..0x3F), use [PacketTypeRange] instead.
 *
 * @param wire The discriminator value matched during decode (0-255). With `@DispatchOn` this
 *   matches the value-class extraction; without `@DispatchOn` it matches the raw byte.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PacketType(
    val wire: Int,
)

/**
 * Variant of a `@ProtocolMessage` sealed interface that claims a contiguous range of wire-byte
 * discriminator values. Use when the LOW bits of the discriminator carry per-instance data
 * (flags, sizes, inline values) rather than type identity, AND the type bits are the
 * HIGH-order bits, so all instances of the variant occupy a contiguous wire-byte range.
 *
 * Examples:
 * - MQTT v5 PUBLISH: 0x30..0x3F (low 4 bits = DUP/QoS/RETAIN).
 * - MessagePack PositiveFixInt: 0x00..0x7F (low 7 bits = value).
 * - CBOR major-type 0x00..0x1F (low 5 bits = additional info).
 *
 * The variant **must** carry the discriminator byte itself in a `@DiscriminatorField` (typically
 * a value class that composes the wire byte from its data fields). KSP validates this — a
 * ranged variant cannot delegate discriminator-encoding to the dispatcher because the byte
 * depends on the variant's data.
 *
 * For non-contiguous bit-packed dispatch where instances of one type are scattered across
 * the byte space (e.g. WebSocket where opcode is the LOW nibble and FIN/RSV occupy HIGH bits,
 * so each opcode's wire bytes are scattered across {0x_1, 0x_2, ...}), this annotation does
 * not apply — use `@DispatchOn` with a value class extracting the type bits, plus [PacketType]
 * matching the extraction.
 *
 * Ranges across all variants of a sealed root must be disjoint (KSP-validated). Mixing
 * [PacketType] singletons with [PacketTypeRange] variants is allowed; their union must remain
 * disjoint.
 *
 * @param from Inclusive low byte of the range (0-255).
 * @param to Inclusive high byte of the range (0-255), must be ≥ [from].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PacketTypeRange(
    val from: Int,
    val to: Int,
)

/**
 * Marks a constructor parameter as carrying the dispatch discriminator for its sealed-interface
 * parent. The annotated field's type must match the parent's `@DispatchOn.type`.
 *
 * On encode, the dispatcher delegates discriminator-byte writing to this field rather than
 * writing the variant's `@PacketType.wire` value itself. This is required for `@PacketTypeRange`
 * variants (the wire byte depends on per-instance data) and useful for `@PacketType` variants
 * whose discriminator carries flag bits the variant needs to read back on decode.
 *
 * ```kotlin
 * @JvmInline
 * @ProtocolMessage
 * value class MqttFixedHeader(val raw: UByte) {
 *     @DispatchValue val packetType: Int get() = (raw.toInt() shr 4) and 0x0F
 * }
 *
 * @DispatchOn(MqttFixedHeader::class)
 * sealed interface MqttControlPacket {
 *     @ProtocolMessage @PacketTypeRange(from = 0x30, to = 0x3F)
 *     data class Publish(
 *         @DiscriminatorField val header: MqttFixedHeader,
 *         val topic: String,
 *     ) : MqttControlPacket
 * }
 * ```
 *
 * Without `@DiscriminatorField` on a ranged variant, the processor cannot know which wire byte
 * to emit — KSP errors with a suggestion to add the marker.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class DiscriminatorField

/**
 * Marks a type parameter as the application payload.
 *
 * The generated codec hands the user a `ReadBuffer` slice of the payload bytes for
 * decoding and a `(WriteBuffer, P) -> Unit` writer for encoding. The slice is valid for
 * the duration of the decode lambda; if the source buffer is backed by a `BufferPool`
 * (e.g. via `StreamProcessor.readBufferScoped`), the pool may recycle the source after
 * the scope returns. Callers that need to retain payload bytes past the callback must
 * copy the slice into a caller-owned buffer explicitly inside the lambda.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class Packet<@Payload P>(
 *     val version: UByte,
 *     @LengthPrefixed val payload: P,
 * )
 * // Generates PacketCodec.decode(buffer, decodePayload = { slice -> ... })
 * // and        PacketCodec.encode(buffer, value, encodePayload = { buf, p -> ... })
 * ```
 */
@Target(AnnotationTarget.TYPE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Payload

/**
 * Length prefix encoding for [LengthPrefixed] fields.
 * All fixed-width variants use big-endian (network) byte order.
 */
enum class LengthPrefix {
    /** 1 byte unsigned (max 255). */
    Byte,

    /** 2 bytes big-endian (default for [LengthPrefixed]). */
    Short,

    /** 4 bytes big-endian. */
    Int,

    /** Variable-byte integer (LEB128 / canonical 7-bit continuation). 1–4 bytes,
     * carrying 0..[com.ditchoom.buffer.VARIABLE_BYTE_INT_MAX].
     *
     * Used by MQTT v5 control packets, Protobuf, gRPC stream IDs, DWARF/WASM LEB128,
     * MIDI variable-length quantities, and CBOR bignums. The encoder reserves the
     * configured cap (`maxBytes`), measures the actual encoded length, writes the
     * canonical VBI, and shifts body bytes left when the canonical width is shorter
     * than the reservation — zero-allocation on the hot path. */
    Varint,
}

/**
 * Marks a String, collection, or nested-message field as length-prefixed: a byte-size
 * prefix followed by the encoded content.
 *
 * **The prefix encodes the byte length of the content**, not the count of items. On
 * decode the field reads the prefix, slices that many bytes from the buffer, and
 * decodes the content from the slice. For collections this means the element codec
 * is invoked repeatedly until the slice is empty — matching MQTT v5 properties,
 * AMQP frames, gRPC, TLS records, HTTP/2, and most binary RPC protocols. If you
 * want a count-prefix instead (e.g. a separate `count: UByte` field that holds the
 * number of elements), use [LengthFrom].
 *
 * ```kotlin
 * @ProtocolMessage
 * data class GreetingMessage(
 *     @LengthPrefixed val name: String,                                          // 2-byte prefix (default)
 *     @LengthPrefixed(LengthPrefix.Byte) val nickname: String,                   // 1-byte prefix (max 255)
 *     @LengthPrefixed(LengthPrefix.Int) val bio: String,                         // 4-byte prefix
 *     @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val notes: String,      // VBI, 1-4 bytes
 *     @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val tags: List<Tag>,    // VBI byte-size + tags until slice empty
 * )
 * ```
 *
 * @param prefix Width / encoding of the prefix.
 * @param maxBytes Spec cap for [LengthPrefix.Varint] (1–4). `0` (default) means use 4
 *   when `prefix == Varint`, ignored for fixed-width prefixes. Encoding a body that
 *   requires a wider VBI than `maxBytes` throws [IllegalArgumentException].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class LengthPrefixed(
    val prefix: LengthPrefix = LengthPrefix.Short,
    val maxBytes: Int = 0,
)

/**
 * Marks an `Int` field as a variable-byte integer (VBI / LEB128 / canonical 7-bit
 * continuation). 1–4 bytes carry values 0..[com.ditchoom.buffer.VARIABLE_BYTE_INT_MAX].
 *
 * The field is read via `ReadBuffer.readVariableByteInteger()`, written via
 * `WriteBuffer.writeVariableByteInteger(value)`, and sized via `variableByteSizeInt(value)`.
 * Used by MQTT v5 control packets, Protobuf, gRPC stream IDs, DWARF/WASM LEB128, MIDI
 * variable-length quantities, and CBOR bignums.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class FrameHeader(
 *     val packetType: UByte,
 *     @VariableByteInteger val remainingLength: Int,
 * )
 * ```
 *
 * Cannot be combined with [@WireBytes][WireBytes], [@WireOrder][WireOrder], or any
 * length annotation — VBI is its own self-delimiting wire form.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class VariableByteInteger

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
 * **Prefer the class-level [Decode] / [Encode] markers** for new code; this enum
 * remains as a one-cycle compatibility shim for the [ProtocolMessage.direction]
 * parameter.
 */
enum class Direction {
    /**
     * Infer from fields — the default. Decode-only if any field is decode-only, etc.
     * Equivalent to omitting both [Decode] and [Encode] class-level markers.
     */
    Default,

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
 * Conditional field: only present on the wire when [expression] evaluates to `true`.
 * The field must be nullable with a default value of `null`.
 *
 * The expression DSL supports two forms in Phase 1:
 * - Boolean field reference: `"hasExtra"` — the named field must be `Boolean`.
 * - Dotted property access: `"flags.willFlag"` — accesses a `Boolean` property on a
 *   value-class field.
 * - Remaining-bytes test: `"remaining >= N"` — passes when at least `N` bytes are
 *   left in the buffer after preceding fields, replacing `@WhenRemaining(N)`.
 *
 * ```kotlin
 * @ProtocolMessage
 * data class OptionalPayload(
 *     val hasExtra: Boolean,
 *     @When("hasExtra") val extra: Int? = null,
 * )
 *
 * @ProtocolMessage
 * data class AckV5(
 *     val packetId: UShort,
 *     @When("remaining >= 1") val reasonCode: UByte? = null,
 * )
 * ```
 *
 * Replaces `@WhenTrue` (boolean / dotted property) and `@WhenRemaining(N)`
 * (`"remaining >= N"`).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class When(
    val expression: String,
)

/**
 * **Deprecated.** Use [When] instead. `@WhenTrue("expr")` is equivalent to `@When("expr")`.
 *
 * Conditional field: only present on the wire when the referenced expression is `true`.
 * The field must be nullable with a default value of `null`.
 *
 * @param expression `"fieldName"` for a Boolean field, or `"fieldName.property"` for a
 *   property on a value class field.
 */
@Deprecated(
    message = "Use @When instead. The expression syntax is identical.",
    replaceWith =
        ReplaceWith(
            "When(expression)",
            "com.ditchoom.buffer.codec.annotations.When",
        ),
    level = DeprecationLevel.WARNING,
)
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class WhenTrue(
    val expression: String,
)

/**
 * **Deprecated.** Use `@When("remaining >= N")` instead. `@WhenRemaining(N)` is
 * equivalent to `@When("remaining >= N")`.
 *
 * Conditional field: only present on the wire when at least [minBytes] remain
 * in the buffer after reading all preceding fields.
 *
 * The field must be nullable with a default value of `null`.
 * All `@WhenRemaining` fields must be contiguous and at the tail of the constructor.
 *
 * @param minBytes Minimum bytes remaining in buffer for this field to be present.
 */
@Deprecated(
    message = "Use @When(\"remaining >= N\") instead.",
    replaceWith =
        ReplaceWith(
            "When(\"remaining >= \" + minBytes)",
            "com.ditchoom.buffer.codec.annotations.When",
        ),
    level = DeprecationLevel.WARNING,
)
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class WhenRemaining(
    val minBytes: Int,
)

/**
 * Delegates field decoding/encoding to an existing [Codec][com.ditchoom.buffer.codec.Codec] object.
 *
 * **Use this only for custom, hand-written codecs** (for example, variable-byte-integer encoders
 * or image-bitmap parsers). If the field's type is itself annotated with [@ProtocolMessage],
 * declare the field with that type directly instead — the processor generates the codec by
 * convention and wires it up automatically, including sealed dispatch and forward references
 * to codecs generated in the same compilation round. `@UseCodec` cannot forward-reference a
 * KSP-generated codec class.
 *
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
 * **For nested @ProtocolMessage types, skip @UseCodec entirely.** Length annotations attach
 * directly to the nested field:
 * ```kotlin
 * @ProtocolMessage
 * data class Frame(
 *     val length: UShort,
 *     @LengthFrom("length") val body: BodyMessage,  // BodyMessage has @ProtocolMessage — no @UseCodec
 * )
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
 * # Body framing (`framing`)
 *
 * Body-length-prefixed dispatch — the wire shape `[discriminator][bodyLength][body]`
 * used by MQTT, AMQP, HTTP/2, and most TLV protocols — is expressed via a
 * [com.ditchoom.buffer.codec.DispatchFraming] strategy.
 *
 * The processor auto-discovers framing by checking whether the discriminator class's
 * companion object implements `DispatchFraming<D>`. When found, the generated codec
 * emits calls to `peekFrameSize` / `readBodyLength` / `writeBodyLength` around variant
 * decode/encode and slices the body. When absent (and no explicit [framing] is set),
 * dispatch is unframed — the variant body consumes the rest of the buffer.
 *
 * ```kotlin
 * @JvmInline
 * value class MqttFixedHeader(val raw: UByte) {
 *     @DispatchValue val packetType: Int get() = (raw.toInt() shr 4) and 0x0F
 *     companion object : DispatchFraming<MqttFixedHeader> {
 *         override fun readBodyLength(buffer: ReadBuffer): Int =
 *             buffer.readVariableByteInteger()
 *         override fun writeBodyLength(buffer: WriteBuffer, n: Int) {
 *             buffer.writeVariableByteInteger(n)
 *         }
 *         override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult { ... }
 *     }
 * }
 *
 * @DispatchOn(MqttFixedHeader::class)
 * sealed interface MqttControlPacket { ... }
 * // Wire shape per variant: [byte1][VBI(remainingLength)][body]
 * ```
 *
 * @param type A `KClass` referencing a `@ProtocolMessage` type (typically a value class)
 *   that contains a `@DispatchValue`-annotated property.
 * @param framing Custom [com.ditchoom.buffer.codec.DispatchFraming] strategy. The default
 *   sentinel [com.ditchoom.buffer.codec.DispatchFraming.Inherit] enables companion-object
 *   discovery on [type]; an explicit framer wins over a discovered companion. The
 *   referenced class must be a Kotlin `object` (companion or named) implementing
 *   `DispatchFraming<D>` where `D` is [type] — KSP errors otherwise.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class DispatchOn(
    val type: kotlin.reflect.KClass<*>,
    val framing: kotlin.reflect.KClass<out com.ditchoom.buffer.codec.DispatchFraming<*>> =
        com.ditchoom.buffer.codec.DispatchFraming.Inherit::class,
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
