package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.JvmCompilationResult
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
 * Compile-time validator coverage for `@DispatchOn` (Stage F slice 6).
 *
 * The bit-packed dispatch shape requires a `@JvmInline value class`
 * discriminator with exactly one `@DispatchValue`-annotated `Int`-
 * returning `val` property, plus variants that are data classes
 * with the discriminator type as their first constructor parameter.
 */
class DispatchOnValidatorTest {
    @Test
    fun acceptsValidShape() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val type: Int get() = raw.toInt() shr 4
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class A(val header: Header = Header(0x10u), val n: UByte) : Packet

                    @PacketType(value = 2)
                    @ProtocolMessage
                    data class B(val header: Header = Header(0x20u), val n: UShort) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@DispatchOn") || result.messages.contains("@DispatchValue"),
            "valid @DispatchOn shape must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenDiscriminatorIsNotValueClass() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*

                @ProtocolMessage
                data class Header(val raw: UByte) {
                    @DispatchValue
                    val type: Int get() = raw.toInt()
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class A(val header: Header = Header(0u), val n: UByte) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("not a `value class`"),
            "diagnostic should name the value-class rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenDiscriminatorHasNoDispatchValueProperty() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    val type: Int get() = raw.toInt()  // no @DispatchValue
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class A(val header: Header = Header(0u), val n: UByte) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@DispatchValue"),
            "diagnostic should name the @DispatchValue requirement. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenDispatchValueReturnsNonInt() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val type: UByte get() = raw  // wrong return type
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class A(val header: Header = Header(0u), val n: UByte) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("non-nullable `Int`"),
            "diagnostic should name the Int return-type rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenMultipleDispatchValueProperties() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue val a: Int get() = raw.toInt() shr 4
                    @DispatchValue val b: Int get() = raw.toInt() and 0xF
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class A(val header: Header = Header(0u), val n: UByte) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("exactly one"),
            "diagnostic should name the single-property rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenVariantMissesDiscriminatorAsFirstField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val type: Int get() = raw.toInt() shr 4
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class A(val n: UByte) : Packet  // missing header field
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("first constructor parameter"),
            "diagnostic should name the first-parameter rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnDuplicatePacketTypeValues() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val type: Int get() = raw.toInt() shr 4
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class A(val header: Header = Header(0x10u), val n: UByte) : Packet

                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class B(val header: Header = Header(0x10u), val m: UByte) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("duplicates"),
            "diagnostic should name the uniqueness rule. Messages:\n${result.messages}",
        )
    }

    private fun compile(
        @Language("kotlin") source: String,
    ): JvmCompilationResult =
        KotlinCompilation()
            .apply {
                sources = listOf(SourceFile.kotlin("Test.kt", source))
                inheritClassPath = true
                messageOutputStream = System.out
                useKsp2()
                configureKsp {
                    symbolProcessorProviders += ProtocolMessageProcessorProvider()
                }
            }.compile()
}
