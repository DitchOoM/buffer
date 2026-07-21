package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.schema.SchemaRecord
import com.ditchoom.buffer.codec.schema.renderSchemaRecords

/**
 * Schema descriptor emitter (SCHEMA_DRIFT.md, step 1).
 *
 * A **pure projection** of the analyzer IR ([CodecShape], [DispatchShape], every [FieldSpec],
 * and the enums reached through [FieldSpec.EnumScalar] fields) into the line-oriented
 * `codec-schema.txt` format. Each record is keyed by its **wire identity** — ordinal for an
 * enum entry, position index for a message field, dispatch value for a sealed variant — never by
 * source-declaration order, so the descriptor diffs cleanly when authors reorder declarations
 * without changing the wire.
 *
 * ```
 * enum com.acme.proto.Intensity default=Normal
 *   0 Normal
 *   1 Bold
 *   2 Faint
 * message com.acme.proto.Login
 *   0 id scalar:Int wire=4B order=Big
 *   1 type scalar:UShort wire=2B order=Big
 *   2 payload string len-prefix=2B/Big
 *   3 ttl? when(sibling:hasTtl) scalar:UInt order=Big
 * sealed com.acme.proto.Op dispatch=fixed-byte/1B framedBy=com.acme.proto.OpLengthCodec
 *   0x12 Scroll
 *   0x13 Resize
 * ```
 *
 * ## Total-coverage contract
 *
 * The field-record builder ([describeField]) is an **exhaustive `when` over [FieldSpec]** — and
 * likewise for [ConditionalInner], [Discriminator], and [LengthSource]. A new field shape fails to
 * compile until its descriptor line is decided, because a descriptor that silently drops a
 * wire-significant attribute is worse than none: it would greenlight a breaking change. Every
 * attribute that changes bytes — scalar kind, wire width, byte order, length-prefix width/order,
 * `@LengthFrom` source, `@RemainingBytes` reserved trailer, the `@When` predicate, the dispatch
 * discriminator kind/width, `@FramedBy`, and the `@ForwardCompatible` sink — must appear in the
 * record. The coverage test (`CodecSchemaDescriptorTest`) asserts every member emits a non-empty
 * line so the exhaustiveness cannot be satisfied with an `else -> ""`.
 */
internal object CodecSchemaDescriptor {
    /**
     * Render the aggregate descriptor for all analyzed shapes. Builds the [SchemaRecord] model
     * (the single format authority, also the parser's target), stably sorts by fully-qualified
     * type name so the same sources always produce byte-identical output, then renders. The enum
     * table is de-duplicated by type (an enum referenced by several fields contributes one record).
     * The result always ends with a trailing newline (or is empty when there is nothing to describe).
     */
    fun render(
        codecShapes: List<CodecShape>,
        dispatchShapes: List<DispatchShape>,
    ): String = renderSchemaRecords(buildRecords(codecShapes, dispatchShapes).sortedBy { it.typeName })

    /** Project analyzer IR into the [SchemaRecord] model (unsorted, in discovery order). */
    fun buildRecords(
        codecShapes: List<CodecShape>,
        dispatchShapes: List<DispatchShape>,
    ): List<SchemaRecord> {
        val records = mutableListOf<SchemaRecord>()

        // Enums are reached only through EnumScalar fields, not as @ProtocolMessage symbols.
        // Dedupe by canonical name (the same enum referenced by N fields is one record); the
        // entryNames/default are identical across references because it is the same declaration.
        val enums = linkedMapOf<String, FieldSpec.EnumScalar>()
        for (shape in codecShapes) {
            for (field in shape.fields) {
                if (field is FieldSpec.EnumScalar) {
                    enums.putIfAbsent(field.enumType.canonicalName, field)
                }
            }
        }
        for (enum in enums.values) records += toEnumRecord(enum)
        for (shape in codecShapes) records += toMessageRecord(shape)
        for (shape in dispatchShapes) records += toSealedRecord(shape)
        return records
    }

    // ---- enum -------------------------------------------------------------

    private fun toEnumRecord(enum: FieldSpec.EnumScalar): SchemaRecord.EnumRecord =
        SchemaRecord.EnumRecord(
            typeName = enum.enumType.canonicalName,
            default = enum.defaultEntryName,
            entries = enum.entryNames.mapIndexed { ordinal, name -> SchemaRecord.EnumRecord.Entry(ordinal, name) },
        )

    // ---- message ----------------------------------------------------------

    private fun toMessageRecord(shape: CodecShape): SchemaRecord.MessageRecord =
        SchemaRecord.MessageRecord(
            typeName = shape.messageClassName.canonicalName,
            fields =
                shape.fields.mapIndexed { position, field ->
                    SchemaRecord.MessageRecord.Field(
                        position = position,
                        name = field.name,
                        optional = field is FieldSpec.Conditional,
                        descriptor = describeField(field),
                    )
                },
        )

    /**
     * The wire-significant descriptor of one field — exhaustive over [FieldSpec]. Returns only the
     * trailing descriptor; the position index, field name, and `?` optional marker are formatted by
     * [describeMessage]. Every arm produces a non-empty token string (enforced by the coverage
     * test).
     */
    fun describeField(field: FieldSpec): String =
        when (field) {
            is FieldSpec.Scalar ->
                "scalar:${field.kind.name} wire=${field.wireBytes}B order=${field.resolvedWireOrder.name}"
            is FieldSpec.EnumScalar ->
                "enum:${field.enumType.canonicalName}"
            is FieldSpec.LengthPrefixedMessage ->
                "msg:${field.messageType.canonicalName} " +
                    "len-prefix=${field.prefixWidth}B/${field.prefixWireOrder.name}"
            is FieldSpec.LengthFromMessage ->
                "msg:${field.messageType.canonicalName} len-from=${describeLengthSource(field.source)}"
            is FieldSpec.LengthPrefixedString ->
                "string len-prefix=${field.prefixWidth}B/${field.prefixWireOrder.name}"
            is FieldSpec.ProtocolMessageScalar ->
                "msg:${field.fieldType.canonicalName}"
            is FieldSpec.UseCodecScalar ->
                "codec:${field.codecType.canonicalName} " +
                    "bounding=${field.isBounding} variable=${field.isVariableLength}"
            is FieldSpec.RemainingBytesString ->
                "string remaining reserved=${field.reservedTrailingBytes}B"
            is FieldSpec.DeferredPayload ->
                // Exhaustive over PayloadExtent: the extent is wire-significant,
                // so each arm spells out its own token layout rather than sharing
                // one. `remaining`/`reserved=` are the historical tokens and must
                // stay exactly where they are — moving them is drift on every
                // message that already carries this shape.
                when (val extent = field.extent) {
                    is PayloadExtent.ToLimit ->
                        "payload:${field.payloadType} remaining " +
                            "codec=${describePayloadCodecSource(field.source)} " +
                            "reserved=${extent.reservedTrailingBytes}B"
                    // Mirrors the `len-from=` token the other @LengthFrom shapes use.
                    // Deliberately shares no tokens with the ToLimit arm: swapping
                    // @RemainingBytes for @LengthFrom must read as drift even though
                    // the bytes can coincide, because the named sibling becomes
                    // load-bearing for framing the moment the payload reads it.
                    is PayloadExtent.Sibling ->
                        "payload:${field.payloadType} len-from=${describeLengthSource(extent.source)} " +
                            "codec=${describePayloadCodecSource(field.source)}"
                }
            is FieldSpec.LengthPrefixedUseCodecList ->
                "list:${field.elementClassName.canonicalName} len-prefix-codec=${field.codecType.canonicalName}"
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                "payload:${field.payloadType} " +
                    "len-prefix=${field.prefixWidth}B/${field.prefixWireOrder.name} " +
                    "codec=${field.payloadCodecType.canonicalName}"
            is FieldSpec.RemainingBytesProtocolMessageList ->
                "list:${field.elementClassName.canonicalName} remaining reserved=${field.reservedTrailingBytes}B"
            is FieldSpec.CountPrefixedProtocolMessageList ->
                "list:${field.elementClassName.canonicalName} count-prefixed"
            is FieldSpec.LengthFromList ->
                "list:${field.elementClassName.canonicalName} len-from=${describeLengthSource(field.source)}"
            is FieldSpec.LengthFromString ->
                "string len-from=${describeLengthSource(field.source)}"
            is FieldSpec.ValueClassScalar ->
                "valueclass:${field.valueClassType.canonicalName} scalar:${field.innerKind.name} " +
                    "wire=${field.wireBytes}B order=${field.valueClassWireOrder.name}"
            is FieldSpec.Conditional ->
                "when(${describeCondition(field.condition)}) ${describeConditionalInner(field.inner)}"
        }

    // ---- conditional inner ------------------------------------------------

    /** Exhaustive over [ConditionalInner] — the bound type of a `@When` field. */
    fun describeConditionalInner(inner: ConditionalInner): String =
        when (inner) {
            is ConditionalInner.Scalar ->
                "scalar:${inner.kind.name} order=${inner.wireOrder.name}"
            is ConditionalInner.LengthPrefixedString ->
                "string len-prefix=${inner.prefixWidth}B/${inner.prefixWireOrder.name}"
            is ConditionalInner.ValueClassScalar ->
                "valueclass:${inner.valueClassType.canonicalName} scalar:${inner.innerKind.name} " +
                    "order=${inner.valueClassWireOrder.name}"
            is ConditionalInner.LengthPrefixedUseCodecList ->
                "list:${inner.elementClassName.canonicalName} len-prefix-codec=${inner.codecType.canonicalName}"
            is ConditionalInner.LengthPrefixedUseCodecPayload ->
                "payload:${inner.payloadType} " +
                    "len-prefix=${inner.prefixWidth}B/${inner.prefixWireOrder.name} " +
                    "codec=${inner.payloadCodecType.canonicalName}"
            is ConditionalInner.UseCodecScalar ->
                "codec:${inner.codecType.canonicalName}"
            is ConditionalInner.ProtocolMessageScalar ->
                "msg:${inner.fieldType.canonicalName}"
        }

    // ---- condition / length source ---------------------------------------

    /** Exhaustive over [ConditionRef] — the resolved source of a `@When` predicate. */
    fun describeCondition(condition: ConditionRef): String =
        when (condition) {
            is ConditionRef.Sibling -> "sibling:${condition.name}"
            is ConditionRef.ValueClassProperty -> "sibling:${condition.siblingName}.${condition.propertyName}"
            is ConditionRef.RemainingCmp -> "remaining${condition.op.symbol}${condition.threshold}"
        }

    /** Exhaustive over [LengthSource] — the resolved carrier of a `@LengthFrom` byte count. */
    fun describeLengthSource(source: LengthSource): String =
        when (source) {
            is LengthSource.Sibling -> "sibling:${source.siblingName}"
            is LengthSource.ValueClassProperty -> "sibling:${source.siblingName}.${source.propertyName}"
        }

    /** Exhaustive over [PayloadCodecSource] — the `Codec<T>` source for a `@RemainingBytes` payload. */
    fun describePayloadCodecSource(source: PayloadCodecSource): String =
        when (source) {
            is PayloadCodecSource.UserCodecObject -> source.codecType.canonicalName
            is PayloadCodecSource.ConstructorInjected -> "injected:${source.parameterName}"
        }

    // ---- sealed -----------------------------------------------------------

    private fun toSealedRecord(shape: DispatchShape): SchemaRecord.SealedRecord =
        SchemaRecord.SealedRecord(
            typeName = shape.parentClassName.canonicalName,
            dispatch = describeDiscriminator(shape.discriminator),
            framedBy =
                when (val f = shape.framing) {
                    is Framing.Framed -> f.config.codecClassName.canonicalName
                    Framing.Unframed -> null
                },
            forwardCompatible =
                when (val fc = shape.forwardCompat) {
                    is ForwardCompat.Enabled -> fc.config.unknownClassName.canonicalName
                    ForwardCompat.Disabled -> null
                },
            // Keyed by dispatch value (stable identity), formatted in the discriminator's label radix.
            variants =
                shape.variants
                    .sortedBy { it.dispatchValue }
                    .map {
                        SchemaRecord.SealedRecord.Variant(
                            label = formatDispatchValue(it.dispatchValue, shape.discriminator.labelFormat),
                            name = it.simpleName,
                        )
                    },
        )

    /**
     * Exhaustive over [Discriminator] — the axis a sealed parent dispatches on, plus its wire width.
     * Sub-attributes are joined with `,` (not spaces) so the whole discriminator is a single
     * space-free token: the sealed header stays tokenizable by `split(' ')` (see [SchemaRecord]).
     */
    fun describeDiscriminator(discriminator: Discriminator): String =
        when (discriminator) {
            Discriminator.FixedByte ->
                "fixed-byte/${describeWireWidth(discriminator.wireWidth)}"
            is Discriminator.ValueClass ->
                "valueclass:${discriminator.className.canonicalName}/${describeWireWidth(discriminator.wireWidth)}," +
                    "inner=${discriminator.innerKind.name}/${discriminator.innerWireOrder.name}," +
                    "dispatchValue=${discriminator.dispatchValueProperty}:${discriminator.dispatchValueKind.name}"
            is Discriminator.Varint ->
                "varint:${discriminator.className.canonicalName}/${describeWireWidth(discriminator.wireWidth)}," +
                    "inner=${discriminator.innerKind.name}," +
                    "dispatchValue=${discriminator.dispatchValueProperty}:${discriminator.dispatchValueKind.name}"
        }

    private fun describeWireWidth(width: WireWidth): String =
        when (width) {
            is WireWidth.Fixed -> "${width.bytes}B"
            WireWidth.Variable -> "var"
        }

    private fun formatDispatchValue(
        value: Int,
        format: LabelFormat,
    ): String =
        when (format) {
            LabelFormat.Hex -> "0x" + value.toString(HEX_RADIX).uppercase().padStart(HEX_BYTE_PAD, '0')
            LabelFormat.Decimal -> value.toString()
        }
}
