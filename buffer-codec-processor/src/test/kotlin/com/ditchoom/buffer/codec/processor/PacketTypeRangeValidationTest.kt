package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PacketTypeRangeValidationTest {
    @Test
    fun `overlapping PacketTypeRange spans rejected with both variants and colliding byte`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class MqttFixedHeader(val raw: UByte) {
                @DispatchValue
                val packetType: Int get() = raw.toInt() shr 4
            }

            @DispatchOn(MqttFixedHeader::class)
            @ProtocolMessage
            sealed interface MqttPacket {
                @PacketTypeRange(0x30, 0x3F)
                @ProtocolMessage
                data class Publish(val header: MqttFixedHeader = MqttFixedHeader(0x30u)) : MqttPacket

                @PacketTypeRange(0x35, 0x4F)
                @ProtocolMessage
                data class Subscribe(val header: MqttFixedHeader = MqttFixedHeader(0x35u)) : MqttPacket
            }
            """,
            )
        val result = compileWithKsp(source)
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected compilation failure for overlapping ranges, got OK:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("spans overlap"),
            "Expected 'spans overlap' substring not found in: ${result.messages}",
        )
        assertTrue(
            result.messages.contains("Publish"),
            "Expected 'Publish' variant name not found in: ${result.messages}",
        )
        assertTrue(
            result.messages.contains("Subscribe"),
            "Expected 'Subscribe' variant name not found in: ${result.messages}",
        )
        assertTrue(
            result.messages.contains("0x35"),
            "Expected colliding byte '0x35' not found in: ${result.messages}",
        )
    }

    @Test
    fun `variant carrying both PacketType and PacketTypeRange rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class FixedHeader(val raw: UByte) {
                @DispatchValue
                val packetType: Int get() = raw.toInt() shr 4
            }

            @DispatchOn(FixedHeader::class)
            @ProtocolMessage
            sealed interface DualAnnotated {
                @PacketType(wire = 1)
                @PacketTypeRange(0x30, 0x3F)
                @ProtocolMessage
                data class Conflicted(val header: FixedHeader = FixedHeader(0x30u)) : DualAnnotated
            }
            """,
            )
        val result = compileWithKsp(source)
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected compilation failure for both annotations on one variant, got OK:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("both @PacketType"),
            "Expected 'both @PacketType' substring not found in: ${result.messages}",
        )
        assertTrue(
            result.messages.contains("Conflicted"),
            "Expected variant name 'Conflicted' not found in: ${result.messages}",
        )
    }

    @Test
    fun `PacketTypeRange variant without discriminator field rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class FixedHeader(val raw: UByte) {
                @DispatchValue
                val packetType: Int get() = raw.toInt() shr 4
            }

            @DispatchOn(FixedHeader::class)
            @ProtocolMessage
            sealed interface NoFieldRoot {
                @PacketTypeRange(0x30, 0x3F)
                @ProtocolMessage
                data class MissingField(val payload: UByte) : NoFieldRoot
            }
            """,
            )
        val result = compileWithKsp(source)
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected compilation failure for missing discriminator field, got OK:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("discriminator field"),
            "Expected 'discriminator field' substring not found in: ${result.messages}",
        )
        assertTrue(
            result.messages.contains("MissingField"),
            "Expected variant name 'MissingField' not found in: ${result.messages}",
        )
    }

    @Test
    fun `onUnknownDiscriminator with unresolvable FQN rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @ProtocolMessage(onUnknownDiscriminator = "fqn.that.does.not.Exist")
            sealed interface UnresolvedRoot {
                @PacketType(wire = 1)
                @ProtocolMessage
                data class A(val x: UByte) : UnresolvedRoot

                @PacketType(wire = 2)
                @ProtocolMessage
                data class B(val y: UByte) : UnresolvedRoot
            }
            """,
            )
        val result = compileWithKsp(source)
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected compilation failure for unresolvable FQN, got OK:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("did not resolve"),
            "Expected 'did not resolve' substring not found in: ${result.messages}",
        )
        assertTrue(
            result.messages.contains("fqn.that.does.not.Exist"),
            "Expected the offending FQN not found in: ${result.messages}",
        )
    }

    @Test
    fun `body-overrun check throws configured onUnknownDiscriminator exception, not IllegalArgumentException`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.BodyLengthFraming
            import com.ditchoom.buffer.codec.annotations.*
            import com.ditchoom.buffer.readVariableByteInteger
            import com.ditchoom.buffer.stream.PeekResult
            import com.ditchoom.buffer.stream.StreamProcessor
            import com.ditchoom.buffer.variableByteSizeInt
            import com.ditchoom.buffer.writeVariableByteInteger

            class FooException(message: String) : RuntimeException(message)

            @JvmInline
            @ProtocolMessage
            value class FramedTag(val raw: UByte) {
                @DispatchValue
                val typeId: Int get() = raw.toInt()

                companion object : BodyLengthFraming<FramedTag> {
                    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
                        PeekResult.NeedsMoreData
                    override fun readBodyLength(buffer: ReadBuffer): Int = buffer.readVariableByteInteger()
                    override fun writeBodyLength(buffer: WriteBuffer, n: Int) { buffer.writeVariableByteInteger(n) }
                    override fun bodyLengthSize(n: Int): Int = variableByteSizeInt(n)
                }
            }

            @DispatchOn(FramedTag::class)
            @ProtocolMessage(onUnknownDiscriminator = "test.FooException")
            sealed interface Framed {
                @PacketType(wire = 1)
                @ProtocolMessage
                data class A(val x: UByte) : Framed

                @PacketType(wire = 2)
                @ProtocolMessage
                data class B(val y: UByte) : Framed
            }
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Compilation failed:\n${result.messages}",
        )
        val codec =
            result.generatedSources.singleOrNull { it.contains("object FramedCodec") }
                ?: error("Expected FramedCodec in generated sources:\n${result.generatedSources}")
        // The body-overrun check used to be `require(_bodySlice.remaining() == 0) { ... }`,
        // which throws IllegalArgumentException. After the fix it must throw the configured
        // exception (FooException) so that wire-malformed packets surface as the protocol's
        // own malformed-packet type instead of a generic IllegalArgumentException.
        assertTrue(
            codec.contains("if (_bodySlice.remaining() != 0)"),
            "Expected `if (_bodySlice.remaining() != 0)` body-overrun guard. Generated:\n$codec",
        )
        assertTrue(
            codec.contains("throw FooException("),
            "Expected body-overrun branch to `throw FooException(...)`. Generated:\n$codec",
        )
        assertTrue(
            !codec.contains("require(_bodySlice.remaining() == 0)"),
            "Expected old `require(_bodySlice.remaining() == 0)` body-overrun check to be gone. Generated:\n$codec",
        )
    }

    @Test
    fun `onUnknownDiscriminator pointing to class without String constructor rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @ProtocolMessage(onUnknownDiscriminator = "kotlin.Unit")
            sealed interface NoStringCtorRoot {
                @PacketType(wire = 1)
                @ProtocolMessage
                data class A(val x: UByte) : NoStringCtorRoot

                @PacketType(wire = 2)
                @ProtocolMessage
                data class B(val y: UByte) : NoStringCtorRoot
            }
            """,
            )
        val result = compileWithKsp(source)
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected compilation failure for class without String constructor, got OK:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("kotlin.Unit"),
            "Expected 'kotlin.Unit' substring not found in: ${result.messages}",
        )
        assertTrue(
            result.messages.contains("`String` constructor"),
            "Expected '`String` constructor' indicator not found in: ${result.messages}",
        )
        // assertEquals here exercises the assertEquals import; sanity-check the root name appears.
        assertEquals(true, result.messages.contains("NoStringCtorRoot"), "Expected root name in: ${result.messages}")
    }
}
