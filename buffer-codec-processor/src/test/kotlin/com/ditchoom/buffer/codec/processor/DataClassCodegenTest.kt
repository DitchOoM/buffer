package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
import com.ditchoom.buffer.codec.processor.spi.FunctionRef
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataClassCodegenTest {
    @Test
    fun `simple two field struct generates codec`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class SimpleStruct(val id: UShort, val value: Int)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `all primitive types compile successfully`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class AllTypes(
                val b: Byte,
                val ub: UByte,
                val s: Short,
                val us: UShort,
                val i: Int,
                val ui: UInt,
                val l: Long,
                val ul: ULong,
                val f: Float,
                val d: Double,
                val bool: Boolean,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `length prefixed string field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class StringMsg(val id: UByte, @LengthPrefixed val name: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `conditional field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class ConditionalMsg(
                val hasName: Boolean,
                @WhenTrue("hasName") @LengthPrefixed val name: String? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `plain class with primary constructor compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            class PlainClass(val id: Int, val value: UByte)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `class without primary constructor causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            class NoPrimaryCtor {
                val id: Int = 0
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("must have a primary constructor")
        assertTrue(hasError, "Expected error for class without primary constructor but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `class with empty primary constructor causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            class EmptyCtor()
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("must have at least one val parameter")
        assertTrue(hasError, "Expected error for empty constructor but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `remaining bytes string field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class TrailingMsg(val id: UByte, @RemainingBytes val data: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `remaining bytes on non-last field causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class BadMsg(@RemainingBytes val data: String, val id: UByte)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("@RemainingBytes can only be used on the last")
        assertTrue(hasError, "Expected error for @RemainingBytes on non-last field but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `length from field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class LengthFromMsg(val length: UShort, @LengthFrom("length") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `payload type parameter generates generic codec`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class PayloadMsg<@Payload P>(
                val id: UShort,
                @LengthPrefixed val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `multiple payload type parameters generate per-type lambdas`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class MultiPayload<@Payload WP, @Payload PP>(
                val id: UShort,
                @LengthPrefixed val willPayload: WP,
                @LengthPrefixed val password: PP,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `payload field without length annotation causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload

            @ProtocolMessage
            data class BadPayload<@Payload P>(
                val id: UShort,
                val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("requires a length annotation")
        assertTrue(hasError, "Expected error for payload without length annotation but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `payload field with remaining bytes`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class TrailingPayload<@Payload P>(
                val id: UShort,
                @RemainingBytes val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `payload field with int length prefix`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthPrefix

            @ProtocolMessage
            data class IntPrefixPayload<@Payload P>(
                val id: UShort,
                @LengthPrefixed(LengthPrefix.Int) val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `wire bytes 3 on Int compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class ThreeByteMsg(val id: UByte, @WireBytes(3) val value: Int)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `wire bytes on Float causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class BadWireBytes(val id: UByte, @WireBytes(2) val value: Float)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("only numeric types")
        assertTrue(hasError, "Expected error for @WireBytes on Float but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `wire bytes exceeding type size causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class BadWireBytes(val id: UByte, @WireBytes(5) val value: Int)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("exceeds")
        assertTrue(hasError, "Expected error for oversized @WireBytes but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `wire bytes 1 on Short compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class OneByteShort(val flags: UByte, @WireBytes(1) val value: Short)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `conflicting LengthPrefixed and RemainingBytes rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class BadMsg(val id: UByte, @LengthPrefixed @RemainingBytes val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `conflicting LengthPrefixed and LengthFrom rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadMsg(val len: UShort, @LengthPrefixed @LengthFrom("len") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `conflicting RemainingBytes and LengthFrom rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadMsg(val len: UShort, @RemainingBytes @LengthFrom("len") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `all three length annotations rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.RemainingBytes
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadMsg(val len: UShort, @LengthPrefixed @RemainingBytes @LengthFrom("len") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `conditional field without default value rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue

            @ProtocolMessage
            data class BadMsg(
                val hasData: Boolean,
                @WhenTrue("hasData") val data: Int? ,
            )
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("must have a default value", ignoreCase = true)
        assertTrue(hasError, "Expected error for conditional without default but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `length prefixed with byte prefix compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthPrefix

            @ProtocolMessage
            data class BytePrefixMsg(val id: UByte, @LengthPrefixed(LengthPrefix.Byte) val name: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    // ──────────────────────── Custom SPI annotation tests ────────────────────────

    @Test
    fun `custom annotation variable byte integer compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VariableByteInteger

            @ProtocolMessage
            data class VbiMsg(val header: UByte, @VariableByteInteger val length: Int)
            """,
            )
        val vbiProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.VariableByteInteger"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
                        writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
                        fixedSize = -1,
                    )
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(vbiProvider))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `custom annotation repeated compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class Repeated(val countField: String)

            @ProtocolMessage
            data class RepMsg(val count: UByte, @Repeated(countField = "count") val items: List<Short>)
            """,
            )
        val repeatedProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.Repeated"

                override fun describe(context: FieldContext): CustomFieldDescriptor {
                    val countField =
                        context.annotationArguments["countField"] as? String
                            ?: error("@Repeated requires countField argument")
                    return CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer.codec.test", "readRepeatedShorts"),
                        writeFunction = FunctionRef("com.ditchoom.buffer.codec.test", "writeRepeatedShorts"),
                        fixedSize = -1,
                        contextFields = listOf(countField),
                    )
                }
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(repeatedProvider))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `custom annotation property bag compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class PropertyBag

            @ProtocolMessage
            data class PropMsg(val version: UByte, @PropertyBag val props: Map<Int, Int>)
            """,
            )
        val propBagProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.PropertyBag"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer.codec.test", "readPropertyBag"),
                        writeFunction = FunctionRef("com.ditchoom.buffer.codec.test", "writePropertyBag"),
                        fixedSize = -1,
                    )
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(propBagProvider))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `custom annotation variable byte integer wrong type rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VariableByteInteger

            @ProtocolMessage
            data class BadVbi(val header: UByte, @VariableByteInteger val name: String)
            """,
            )
        val vbiProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.VariableByteInteger"

                override fun describe(context: FieldContext): CustomFieldDescriptor {
                    require(context.typeName == "kotlin.Int") {
                        "@VariableByteInteger can only be applied to Int fields, found: ${context.typeName}"
                    }
                    return CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
                        writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
                    )
                }
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(vbiProvider))
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("can only be applied to Int fields")
        assertTrue(hasError, "Expected type mismatch error but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `cannot override built-in annotation`() {
        val overrideProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "com.ditchoom.buffer.codec.annotations.LengthPrefixed"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("test", "read"),
                        writeFunction = FunctionRef("test", "write"),
                    )
            }
        val result = compileWithKspAndCustomProviders(providers = listOf(overrideProvider))
        val hasError =
            result.exitCode != KotlinCompilation.ExitCode.OK ||
                result.messages.contains("cannot override built-in annotation", ignoreCase = true)
        assertTrue(hasError, "Expected built-in override rejection but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `duplicate provider fqn conflict rejected`() {
        val provider1 =
            object : CodecFieldProvider {
                override val annotationFqn = "test.DuplicateAnnotation"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("test", "read1"),
                        writeFunction = FunctionRef("test", "write1"),
                    )
            }
        val provider2 =
            object : CodecFieldProvider {
                override val annotationFqn = "test.DuplicateAnnotation"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("test", "read2"),
                        writeFunction = FunctionRef("test", "write2"),
                    )
            }
        val result = compileWithKspAndCustomProviders(providers = listOf(provider1, provider2))
        val hasError =
            result.exitCode != KotlinCompilation.ExitCode.OK ||
                result.messages.contains("Duplicate CodecFieldProvider", ignoreCase = true)
        assertTrue(hasError, "Expected duplicate provider rejection but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `provider describe throws propagates as ksp error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class Broken

            @ProtocolMessage
            data class BrokenMsg(val id: UByte, @Broken val data: Int)
            """,
            )
        val brokenProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.Broken"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    throw IllegalArgumentException("intentional test failure")
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(brokenProvider))
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("intentional test failure")
        assertTrue(hasError, "Expected provider error propagation but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `custom annotation breaks batch`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VariableByteInteger

            @ProtocolMessage
            data class BatchBreaker(val a: Byte, @VariableByteInteger val b: Int, val c: Byte)
            """,
            )
        val vbiProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.VariableByteInteger"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
                        writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
                        fixedSize = -1,
                    )
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(vbiProvider))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `mixed built-in and custom annotations compile`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VariableByteInteger

            @ProtocolMessage
            data class MixedMsg(
                val id: UShort,
                @LengthPrefixed val name: String,
                @VariableByteInteger val remaining: Int,
                val checksum: Byte,
            )
            """,
            )
        val vbiProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.VariableByteInteger"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
                        writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
                        fixedSize = -1,
                    )
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(vbiProvider))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `conditional custom field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VariableByteInteger

            @ProtocolMessage
            data class ConditionalVbi(
                val hasVbi: Boolean,
                @WhenTrue("hasVbi") @VariableByteInteger val vbi: Int? = null,
            )
            """,
            )
        val vbiProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.VariableByteInteger"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
                        writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
                        fixedSize = -1,
                    )
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(vbiProvider))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `multiple custom annotations on same field uses first match`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VariableByteInteger

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class PropertyBag

            @ProtocolMessage
            data class MultiAnnotated(val id: UByte, @VariableByteInteger @PropertyBag val data: Int)
            """,
            )
        val vbiProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.VariableByteInteger"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
                        writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
                        fixedSize = -1,
                    )
            }
        val propBagProvider =
            object : CodecFieldProvider {
                override val annotationFqn = "test.PropertyBag"

                override fun describe(context: FieldContext): CustomFieldDescriptor =
                    CustomFieldDescriptor(
                        readFunction = FunctionRef("com.ditchoom.buffer.codec.test", "readPropertyBag"),
                        writeFunction = FunctionRef("com.ditchoom.buffer.codec.test", "writePropertyBag"),
                        fixedSize = -1,
                    )
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(vbiProvider, propBagProvider))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    // ──────────────────────── List<NestedMessage> codegen tests ────────────────────────

    @Test
    fun `list of nested message with LengthFrom compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class Entry(val value: Short)

            @ProtocolMessage
            data class Container(val count: UByte, @LengthFrom("count") val items: List<Entry>)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `list of nested message with RemainingBytes compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class Entry(val value: Short)

            @ProtocolMessage
            data class Container(val header: UByte, @RemainingBytes val items: List<Entry>)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `list of nested message with LengthPrefixed compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthPrefix

            @ProtocolMessage
            data class Entry(val value: Short)

            @ProtocolMessage
            data class Container(val header: UByte, @LengthPrefixed(LengthPrefix.Byte) val items: List<Entry>)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `list of primitive type rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadList(val count: UByte, @LengthFrom("count") val items: List<Short>)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("not supported")
        assertTrue(hasError, "Expected error for List<Short> but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `list without length annotation rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class Entry(val value: Short)

            @ProtocolMessage
            data class BadList(val items: List<Entry>)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("requires a length annotation")
        assertTrue(hasError, "Expected error for List without length annotation but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `list of non-protocol-message class rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            data class PlainEntry(val value: Short)

            @ProtocolMessage
            data class BadList(val count: UByte, @LengthFrom("count") val items: List<PlainEntry>)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("must be annotated with @ProtocolMessage")
        assertTrue(hasError, "Expected error for List of non-@ProtocolMessage but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `length from zero count produces empty list`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class Entry(val value: Short)

            @ProtocolMessage
            data class ZeroCount(val count: UByte, @LengthFrom("count") val items: List<Entry>)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    // ── Bug fix tests ──

    @Test
    fun `payload only class generates empty context object and compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class BinaryData<@Payload D>(
                @LengthPrefixed val data: D,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `DispatchOn sealed with mixed payload and non-payload variants compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class MyHeader(val raw: UByte) {
                @DispatchValue
                val packetType: Int get() = raw.toInt() and 0x0F
            }

            @DispatchOn(MyHeader::class)
            @ProtocolMessage
            sealed interface MyProtocol {
                @PacketType(value = 1, wire = 0x01)
                @ProtocolMessage
                @JvmInline
                value class Simple(val x: UInt) : MyProtocol

                @PacketType(value = 2, wire = 0x02)
                @ProtocolMessage
                data class WithPayload<@Payload D>(
                    val len: UShort,
                    @LengthFrom("len") val data: D,
                ) : MyProtocol
            }
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }
}
