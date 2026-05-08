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
 * Compile-time validator coverage for `@DispatchOn`.
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
    fun firesWhenDispatchValueReturnsUnsupportedKind() {
        // Slice — accepted return types are
        // {Boolean, Byte, UByte, Short, UShort, Int, UInt}. Long
        // (and ULong) stay rejected because `@PacketType.value` is
        // an `Int` and can't address values beyond `Int.MAX_VALUE`.
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
                    val type: Long get() = raw.toLong()  // unsupported return type
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
            result.messages.contains("must return one of {Boolean, Byte, UByte, Short, UShort, Int, UInt}"),
            "diagnostic should list the accepted return types. Messages:\n${result.messages}",
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

    @Test
    fun acceptsBooleanReturnType() {
        // Slice — Boolean dispatch (e.g. QUIC
        // long-header form bit). Validator accepts; range is 0..1.
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
                    val highBit: Boolean get() = (raw.toUInt() and 0x80u) != 0u
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 0)
                    @ProtocolMessage
                    data class Low(val header: Header = Header(0x40u)) : Packet

                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class High(val header: Header = Header(0xC0u)) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(result.messages.contains("error:", ignoreCase = true), result.messages)
    }

    @Test
    fun rejectsBooleanPacketTypeOutOfRange() {
        // Boolean range is 0..1; 2 must trip the per-kind range check.
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
                    val highBit: Boolean get() = (raw.toUInt() and 0x80u) != 0u
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Packet {
                    @PacketType(value = 0)
                    @ProtocolMessage
                    data class Low(val header: Header = Header(0u)) : Packet

                    @PacketType(value = 2)  // out of Boolean range
                    @ProtocolMessage
                    data class TooHigh(val header: Header = Header(0u)) : Packet
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("out of range") &&
                result.messages.contains("kotlin.Boolean") &&
                result.messages.contains("0..1"),
            "diagnostic should name the Boolean range. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsUShortReturnTypeWithWideValues() {
        // Slice — UShort dispatch (e.g. Ethernet
        // EtherType). Discriminator inner is UShort (2 wire bytes);
        // PacketType values exceed the old 0..255 cap and must be
        // accepted now.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class EtherType(val raw: UShort) {
                    @DispatchValue
                    val type: UShort get() = raw
                }

                @DispatchOn(EtherType::class)
                @ProtocolMessage
                sealed interface Frame {
                    @PacketType(value = 0x0800)
                    @ProtocolMessage
                    data class Ipv4(val type: EtherType = EtherType(0x0800u)) : Frame

                    @PacketType(value = 0x86DD)
                    @ProtocolMessage
                    data class Ipv6(val type: EtherType = EtherType(0x86DDu)) : Frame
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(result.messages.contains("error:", ignoreCase = true), result.messages)
    }

    @Test
    fun rejectsUShortPacketTypeOutOfRange() {
        // UShort range is 0..65535; 0x10000 must trip the per-kind range check.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.*
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class EtherType(val raw: UShort) {
                    @DispatchValue
                    val type: UShort get() = raw
                }

                @DispatchOn(EtherType::class)
                @ProtocolMessage
                sealed interface Frame {
                    @PacketType(value = 0x10000)  // 65536 — out of UShort range
                    @ProtocolMessage
                    data class TooBig(val type: EtherType = EtherType(0u)) : Frame
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("out of range") &&
                result.messages.contains("kotlin.UShort") &&
                result.messages.contains("0..65535"),
            "diagnostic should name the UShort range. Messages:\n${result.messages}",
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
