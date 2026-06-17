package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.schema.CodecSchemaParser
import com.ditchoom.buffer.codec.schema.SchemaRecord
import com.ditchoom.buffer.codec.schema.renderSchemaRecords
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip + tokenization tests for the schema parser (SCHEMA_DRIFT.md, "dogfood both ways").
 *
 * The emitter ([CodecSchemaDescriptor]) and the parser ([CodecSchemaParser]) share one
 * [SchemaRecord] model; these tests lock them together so the descriptor format can never drift out
 * from under the step-2 plugin's differ.
 */
class CodecSchemaParserTest {
    /**
     * A representative descriptor exercising every record kind and the tricky bits: an enum with and
     * without a default, a message with an optional `@When` field and multi-token descriptors, and
     * sealed records with value-class / varint / fixed-byte discriminators plus `framedBy` and
     * `forwardCompatible`. The discriminator token is comma-internal (single space-free token).
     */
    private val richText =
        """
        enum com.acme.Color default=Unknown
          0 Unknown
          1 Red
        enum com.acme.Plain
          0 A
          1 B
        message com.acme.Login
          0 id scalar:Int wire=4B order=Big
          1 ttl? when(sibling:hasTtl) scalar:UInt order=Big
          2 name string len-prefix=2B/Big
        sealed com.acme.Frame dispatch=valueclass:com.acme.Tag/2B,inner=UShort/Big,dispatchValue=type:Int framedBy=com.acme.LenCodec forwardCompatible=com.acme.Frame.Unknown
          2048 Ipv4
          2054 Arp
        sealed com.acme.Op dispatch=fixed-byte/1B
          0x12 Scroll
          0x13 Resize
        sealed com.acme.VFrame dispatch=varint:com.acme.VType/var,inner=ULong,dispatchValue=type:Int
          0 Data
          64 Ext
        """.trimIndent() + "\n"

    @Test
    fun `render-parse-render is identity on a representative descriptor`() {
        val once = CodecSchemaParser.parse(richText)
        val rendered = renderSchemaRecords(once)
        assertEquals(richText, rendered, "renderSchemaRecords(parse(text)) must equal text")
        // And re-parsing the re-rendered text yields an equal model (full fixed point).
        assertEquals(once, CodecSchemaParser.parse(rendered))
    }

    @Test
    fun `parse recovers structured record fields`() {
        val records = CodecSchemaParser.parse(richText).associateBy { it.typeName }

        val color = records["com.acme.Color"] as SchemaRecord.EnumRecord
        assertEquals("Unknown", color.default)
        assertEquals(listOf(0 to "Unknown", 1 to "Red"), color.entries.map { it.ordinal to it.name })

        assertEquals(null, (records["com.acme.Plain"] as SchemaRecord.EnumRecord).default)

        val login = records["com.acme.Login"] as SchemaRecord.MessageRecord
        val ttl = login.fields[1]
        assertEquals("ttl", ttl.name)
        assertTrue(ttl.optional, "the @When field must round-trip its '?' marker")
        assertEquals("when(sibling:hasTtl) scalar:UInt order=Big", ttl.descriptor)

        val frame = records["com.acme.Frame"] as SchemaRecord.SealedRecord
        assertEquals("valueclass:com.acme.Tag/2B,inner=UShort/Big,dispatchValue=type:Int", frame.dispatch)
        assertEquals("com.acme.LenCodec", frame.framedBy)
        assertEquals("com.acme.Frame.Unknown", frame.forwardCompatible)
        assertEquals(listOf("2048" to "Ipv4", "2054" to "Arp"), frame.variants.map { it.label to it.name })

        val op = records["com.acme.Op"] as SchemaRecord.SealedRecord
        assertEquals("fixed-byte/1B", op.dispatch)
        assertEquals(null, op.framedBy)
        assertEquals(null, op.forwardCompatible)
    }

    /**
     * Every structural token — type name, each header attribute value, enum/variant labels and
     * names, field positions and names — must be space-free, so headers tokenize by `split(' ')`.
     * Only a message field's [descriptor] may carry spaces (it is an opaque comparison unit). The
     * comma-joined discriminator exists precisely to honor this.
     */
    @Test
    fun `structural tokens are space-free`() {
        assertStructuralTokensSpaceFree(CodecSchemaParser.parse(richText))
    }

    @Test
    fun `emitter output round-trips through the parser`() {
        // Build directly off the IR so the emitter (not a literal) is the source of truth.
        val message =
            CodecShape(
                packageName = "com.acme",
                messageClassName = ClassName("com.acme", "M"),
                ownerSimpleName = "M",
                codecSimpleName = "MCodec",
                fields =
                    listOf(
                        FieldSpec.Scalar("id", ScalarKind.Int, Endianness.Big, 4),
                        FieldSpec.Conditional(
                            "ttl",
                            "M",
                            ConditionRef.Sibling("hasTtl"),
                            ClassName("com.acme", "X"),
                            ConditionalInner.Scalar(ScalarKind.UInt, Endianness.Big),
                        ),
                    ),
            )
        val sealed =
            DispatchShape(
                packageName = "com.acme",
                parentClassName = ClassName("com.acme", "Frame"),
                parentSimpleName = "Frame",
                codecSimpleName = "FrameCodec",
                discriminator =
                    Discriminator.ValueClass(
                        className = ClassName("com.acme", "Tag"),
                        codecClassName = ClassName("com.acme", "TagCodec"),
                        innerKind = ScalarKind.UShort,
                        innerWireOrder = Endianness.Big,
                        dispatchValueProperty = "type",
                        dispatchValueKind = ScalarKind.Int,
                    ),
                variants =
                    listOf(
                        DispatchVariant(
                            "Ipv4",
                            ClassName("com.acme", "Ipv4"),
                            ClassName("com.acme", "Ipv4Codec"),
                            2048,
                            VariantCodecRef.StaticObject,
                            VariantWireSize.BackPatch,
                        ),
                    ),
                genericity = Genericity.Monomorphic,
                framing = Framing.Framed(FramedByConfig(ClassName("com.acme", "LenCodec"), "")),
                forwardCompat =
                    ForwardCompat.Enabled(
                        ForwardCompatibleConfig(ClassName("com.acme", "Frame.Unknown"), "code", "raw", ScalarKind.Long),
                    ),
                visibility = CodecVisibility.Public,
            )

        val emitted = CodecSchemaDescriptor.render(listOf(message), listOf(sealed))
        assertEquals(emitted, renderSchemaRecords(CodecSchemaParser.parse(emitted)))
        assertStructuralTokensSpaceFree(CodecSchemaParser.parse(emitted))
    }

    private fun assertStructuralTokensSpaceFree(records: List<SchemaRecord>) {
        fun noSpace(
            what: String,
            value: String?,
        ) = assertFalse(value?.contains(' ') == true, "$what must be space-free, was '$value'")

        for (record in records) {
            noSpace("type name", record.typeName)
            when (record) {
                is SchemaRecord.EnumRecord -> {
                    noSpace("enum default", record.default)
                    record.entries.forEach { noSpace("enum entry name", it.name) }
                }
                is SchemaRecord.MessageRecord ->
                    // Field name must be space-free; the descriptor is intentionally exempt.
                    record.fields.forEach { noSpace("field name", it.name) }
                is SchemaRecord.SealedRecord -> {
                    noSpace("dispatch", record.dispatch)
                    noSpace("framedBy", record.framedBy)
                    noSpace("forwardCompatible", record.forwardCompatible)
                    record.variants.forEach {
                        noSpace("variant label", it.label)
                        noSpace("variant name", it.name)
                    }
                }
            }
        }
    }
}
