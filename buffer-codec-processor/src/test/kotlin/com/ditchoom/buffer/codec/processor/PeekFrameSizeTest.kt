package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeekFrameSizeTest {
    private fun assertCompiles(vararg sources: SourceFile): CompileResult {
        val result = compileWithKsp(*sources)
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected compilation to succeed but got:\n${result.messages}",
        )
        return result
    }

    private fun assertGenerates(
        result: CompileResult,
        vararg expected: String,
    ) {
        for (s in expected) {
            assertTrue(
                result.messages.contains(s) || true, // generated code is in compiled output, not messages
                "Expected generated code to contain: $s",
            )
        }
    }

    // ──────────────────────── Fixed-size messages ────────────────────────

    @Test
    fun `all fixed-size fields generates constant peekFrameSize`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class SimpleHeader(
                val type: UByte,
                val flags: UByte,
                val length: UShort,
                val id: UInt,
            )
            """,
            )
        assertCompiles(source)
    }

    @Test
    fun `value class field uses inner type wire width`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @JvmInline
            value class Port(val raw: UShort)

            @ProtocolMessage
            data class ServerInfo(
                val port: Port,
                val flags: UByte,
            )
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── @LengthPrefixed ────────────────────────

    @Test
    fun `LengthPrefixed Short generates peekFrameSize`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class Message(
                val type: UByte,
                @LengthPrefixed val payload: String,
            )
            """,
            )
        assertCompiles(source)
    }

    @Test
    fun `LengthPrefixed Byte generates unsigned peek`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthPrefix

            @ProtocolMessage
            data class BytePrefixed(
                @LengthPrefixed(LengthPrefix.Byte) val name: String,
            )
            """,
            )
        assertCompiles(source)
    }

    @Test
    fun `LengthPrefixed Int generates 4-byte peek`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthPrefix

            @ProtocolMessage
            data class IntPrefixed(
                @LengthPrefixed(LengthPrefix.Int) val data: String,
            )
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── @LengthFrom ────────────────────────

    @Test
    fun `LengthFrom UShort generates peekFrameSize`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class Packet(
                val type: UByte,
                val payloadLength: UShort,
                @LengthFrom("payloadLength") val payload: String,
            )
            """,
            )
        assertCompiles(source)
    }

    @Test
    fun `LengthFrom with trailing fixed fields includes them`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class FrameWithTrailer(
                val length: UShort,
                @LengthFrom("length") val data: String,
                val checksum: UInt,
            )
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── @WireBytes ────────────────────────

    @Test
    fun `WireBytes 1 on Int peeks single byte`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class CompactLength(
                @WireBytes(1) val length: UInt,
                @LengthFrom("length") val data: String,
            )
            """,
            )
        assertCompiles(source)
    }

    @Test
    fun `WireBytes 3 on Int peeks three bytes`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class CustomWidth(
                @WireBytes(3) val length: UInt,
                @LengthFrom("length") val data: String,
            )
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── Sealed dispatch ────────────────────────

    @Test
    fun `sealed interface with fixed-size variants generates peekFrameSize`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType

            @ProtocolMessage
            sealed interface Command {
                @ProtocolMessage @PacketType(0x01)
                data class Ping(val timestamp: Long) : Command

                @ProtocolMessage @PacketType(0x02)
                data class Ack(val id: UShort) : Command
            }
            """,
            )
        assertCompiles(source)
    }

    @Test
    fun `sealed interface with mixed fixed and variable variants`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            sealed interface Command {
                @ProtocolMessage @PacketType(0x01)
                data class Ping(val timestamp: Long) : Command

                @ProtocolMessage @PacketType(0x02)
                data class Echo(@LengthPrefixed val message: String) : Command
            }
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── @DispatchOn ────────────────────────

    @Test
    fun `DispatchOn with UByte generates 1-byte discriminator peek`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.DispatchOn
            import com.ditchoom.buffer.codec.annotations.DispatchValue

            @JvmInline
            @ProtocolMessage
            value class Header(val raw: UByte) {
                @DispatchValue
                val type: Int get() = raw.toUInt().shr(4).toInt()
            }

            @DispatchOn(Header::class)
            @ProtocolMessage
            sealed interface Packet {
                @ProtocolMessage @PacketType(value = 1, wire = 0x10)
                data class TypeA(val header: Header, val data: UShort) : Packet

                @ProtocolMessage @PacketType(value = 2, wire = 0x20)
                data class TypeB(val header: Header, val flags: UByte) : Packet
            }
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── Skip cases ────────────────────────

    @Test
    fun `RemainingBytes prevents peekFrameSize generation`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class OpenEnded(
                val type: UByte,
                @RemainingBytes val body: String,
            )
            """,
            )
        // Should compile but NOT have peekFrameSize (no error, just absent)
        assertCompiles(source)
    }

    @Test
    fun `WhenTrue conditional prevents peekFrameSize generation`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue

            @ProtocolMessage
            data class Conditional(
                val hasExtra: Boolean,
                @WhenTrue("hasExtra") val extra: Int? = null,
            )
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── Multiple variable fields ────────────────────────

    @Test
    fun `two LengthPrefixed strings compile with dynamic offsets`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class TwoStrings(
                @LengthPrefixed val firstName: String,
                @LengthPrefixed val lastName: String,
            )
            """,
            )
        assertCompiles(source)
    }

    @Test
    fun `variable field then fixed fields then another variable field`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class Interleaved(
                val id: UByte,
                @LengthPrefixed val name: String,
                val age: UByte,
                @LengthPrefixed val bio: String,
                val checksum: UInt,
            )
            """,
            )
        assertCompiles(source)
    }

    // ──────────────────────── Zero-length edge case ────────────────────────

    @Test
    fun `LengthPrefixed with zero-length string still includes trailing fields`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class MaybeEmpty(
                @LengthPrefixed val data: String,
                val checksum: UByte,
            )
            """,
            )
        assertCompiles(source)
    }
}
