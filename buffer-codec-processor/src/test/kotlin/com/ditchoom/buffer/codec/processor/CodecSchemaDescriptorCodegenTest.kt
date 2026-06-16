package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.schema.CodecSchemaParser
import com.ditchoom.buffer.codec.schema.SchemaRecord
import com.ditchoom.buffer.codec.schema.renderSchemaRecords
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KSP end-to-end coverage for the schema descriptor (SCHEMA_DRIFT.md, step 1): the processor's
 * `finish()` must drop an aggregate `codec-schema.txt` into the generated output, sorted by type
 * name, covering enum / message / sealed records across all `@ProtocolMessage` types in the round.
 */
class CodecSchemaDescriptorCodegenTest {
    @Test
    fun `processor emits a sorted aggregate codec-schema descriptor`() {
        val (result, schema) =
            compileAndReadSchema(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.EnumDefault
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.Endianness

                enum class Intensity {
                    @EnumDefault Normal,
                    Bold,
                    Faint,
                }

                @ProtocolMessage(wireOrder = Endianness.Big)
                data class Login(
                    val id: Int,
                    val type: UShort,
                    val intensity: Intensity,
                    @LengthPrefixed val name: String,
                )

                @ProtocolMessage
                sealed interface Op {
                    @ProtocolMessage @PacketType(0x12)
                    data class Scroll(val by: UByte) : Op

                    @ProtocolMessage @PacketType(0x13)
                    data class Resize(val w: UByte) : Op
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        assertTrue(schema.isNotEmpty(), "codec-schema.txt was not generated")

        // Enum projected from the EnumScalar field, keyed by ordinal in declaration order.
        assertTrue(
            schema.contains("enum test.Intensity default=Normal\n  0 Normal\n  1 Bold\n  2 Faint"),
            "enum record missing/incorrect:\n$schema",
        )
        // Message fields keyed by position, carrying kind/width/order + framing.
        assertTrue(schema.contains("message test.Login"), "message record missing:\n$schema")
        assertTrue(schema.contains("  0 id scalar:Int wire=4B order=Big"), "field 0 wrong:\n$schema")
        assertTrue(schema.contains("  1 type scalar:UShort wire=2B order=Big"), "field 1 wrong:\n$schema")
        assertTrue(schema.contains("  2 intensity enum:test.Intensity"), "enum field wrong:\n$schema")
        assertTrue(schema.contains("  3 name string len-prefix=2B/Big"), "len-prefixed field wrong:\n$schema")
        // Sealed variants keyed by @PacketType, hex labels, sorted by dispatch value.
        assertTrue(
            schema.contains("sealed test.Op dispatch=fixed-byte/1B\n  0x12 Scroll\n  0x13 Resize"),
            "sealed record missing/incorrect:\n$schema",
        )

        // Records sorted by fully-qualified type name.
        val typeNames =
            schema
                .lines()
                .filter { it.startsWith("enum ") || it.startsWith("message ") || it.startsWith("sealed ") }
                .map { it.split(' ')[1] }
        assertEquals(typeNames.sorted(), typeNames, "records must be sorted by type name:\n$schema")
    }

    @Test
    fun `generated descriptor round-trips through the parser`() {
        val schema =
            compileAndReadSchema(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.EnumDefault
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed

                enum class Color { @EnumDefault Unknown, Red, Green }

                @JvmInline
                @ProtocolMessage
                value class EtherType(val raw: UShort) {
                    @DispatchValue val type: Int get() = raw.toInt()
                }

                @DispatchOn(EtherType::class)
                @ProtocolMessage
                sealed interface Frame {
                    @ProtocolMessage @PacketType(value = 2048, wire = 2048)
                    data class Ipv4(val etherType: EtherType, val color: Color, @LengthPrefixed val name: String) : Frame
                }
                """.trimIndent(),
            ).second
        assertTrue(schema.isNotEmpty(), "schema not generated")
        // The emitter's real output, re-rendered from the parsed model, must be byte-identical —
        // emit and parse are locked against each other (dogfood both ways).
        assertEquals(schema, renderSchemaRecords(CodecSchemaParser.parse(schema)))
        // The value-class discriminator (a multi-attribute token) survives as one space-free token.
        val frame =
            CodecSchemaParser.parse(schema).filterIsInstance<SchemaRecord.SealedRecord>().single { it.typeName == "test.Frame" }
        assertFalse(frame.dispatch.contains(' '), "discriminator must be one space-free token: '${frame.dispatch}'")
        assertTrue(frame.dispatch.startsWith("valueclass:test.EtherType/2B,"), "unexpected dispatch token: ${frame.dispatch}")
    }

    @Test
    fun `descriptor is byte-identical across two compilations`() {
        val source =
            """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class A(val x: Int, val y: UShort)

            @ProtocolMessage
            data class B(val a: UByte)
            """.trimIndent()
        val first = compileAndReadSchema(source).second
        val second = compileAndReadSchema(source).second
        assertTrue(first.isNotEmpty(), "schema not generated")
        assertEquals(first, second, "same sources must produce a byte-identical descriptor")
    }

    private fun compileAndReadSchema(
        @Language("kotlin") source: String,
    ): Pair<com.tschuchort.compiletesting.JvmCompilationResult, String> {
        val compilation =
            KotlinCompilation().apply {
                sources = listOf(SourceFile.kotlin("Test.kt", source))
                inheritClassPath = true
                messageOutputStream = System.out
                useKsp2()
                configureKsp {
                    symbolProcessorProviders += ProtocolMessageProcessorProvider()
                }
            }
        val result = compilation.compile()
        val schema =
            compilation.workingDir
                .walkTopDown()
                .firstOrNull { it.isFile && it.name == "codec-schema.txt" }
                ?.readText()
                ?: ""
        return result to schema
    }
}
