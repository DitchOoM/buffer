package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectionValidationTest {
    // ──────────────────── Basic @UseCodec direction detection ────────────────────

    @Test
    fun `UseCodec with Decoder compiles as decode-only`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbDecoder : Decoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }

            @ProtocolMessage
            data class DecodeOnlyPoint(
                val x: Int,
                @UseCodec(RgbDecoder::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `UseCodec with Encoder compiles as encode-only`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.Encoder
            import com.ditchoom.buffer.codec.SizeEstimate
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbEncoder : Encoder<Rgb> {
                override fun encode(buffer: WriteBuffer, value: Rgb) {
                    buffer.writeUByte(value.r); buffer.writeUByte(value.g); buffer.writeUByte(value.b)
                }
            }

            @ProtocolMessage
            data class EncodeOnlyPoint(
                val x: Int,
                @UseCodec(RgbEncoder::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `UseCodec with Codec compiles as bidirectional`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.Codec
            import com.ditchoom.buffer.codec.DecodeContext
            import com.ditchoom.buffer.codec.EncodeContext
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbCodec : Codec<Rgb> {
                override fun decode(buffer: ReadBuffer, context: DecodeContext): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
                override fun encode(buffer: WriteBuffer, value: Rgb, context: EncodeContext) {
                    buffer.writeUByte(value.r); buffer.writeUByte(value.g); buffer.writeUByte(value.b)
                }
            }

            @ProtocolMessage
            data class BidirectionalPoint(
                val x: Int,
                @UseCodec(RgbCodec::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `UseCodec with both Decoder and Encoder but not Codec compiles as bidirectional`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.Encoder
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbBothWays : Decoder<Rgb>, Encoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
                override fun encode(buffer: WriteBuffer, value: Rgb) {
                    buffer.writeUByte(value.r); buffer.writeUByte(value.g); buffer.writeUByte(value.b)
                }
            }

            @ProtocolMessage
            data class BothWaysPoint(
                val x: Int,
                @UseCodec(RgbBothWays::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `UseCodec with non-codec class errors with helpful message`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object NotACodec

            @ProtocolMessage
            data class BadPoint(
                val x: Int,
                @UseCodec(NotACodec::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("does not implement") &&
                result.messages.contains("Codec<T>") &&
                result.messages.contains("Decoder<T>") &&
                result.messages.contains("Encoder<T>"),
            "Expected helpful error about missing interface. Got: ${result.messages}",
        )
    }

    @Test
    fun `UseCodec with empty interface errors`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            interface MyInterface
            object BadCodec : MyInterface

            @ProtocolMessage
            data class BadPoint(
                val x: Int,
                @UseCodec(BadCodec::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("does not implement"),
            "Expected error about missing interface. Got: ${result.messages}",
        )
    }

    // ──────────────────── Direction enum validation ────────────────────

    @Test
    fun `direction Codec with decode-only field errors`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.annotations.Direction
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbDecoder : Decoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }

            @ProtocolMessage(direction = Direction.Codec)
            data class ForcedBiPoint(
                val x: Int,
                @UseCodec(RgbDecoder::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("direction = Codec") &&
                result.messages.contains("bidirectional") &&
                result.messages.contains("decode-only"),
            "Expected error about bidirectional requirement. Got: ${result.messages}",
        )
    }

    @Test
    fun `direction Codec with encode-only field errors`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.Encoder
            import com.ditchoom.buffer.codec.annotations.Direction
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbEncoder : Encoder<Rgb> {
                override fun encode(buffer: WriteBuffer, value: Rgb) {
                    buffer.writeUByte(value.r)
                }
            }

            @ProtocolMessage(direction = Direction.Codec)
            data class ForcedBiPoint(
                val x: Int,
                @UseCodec(RgbEncoder::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("direction = Codec") &&
                result.messages.contains("encode-only"),
            "Expected error about bidirectional requirement. Got: ${result.messages}",
        )
    }

    @Test
    fun `direction DecodeOnly with encode-only field errors`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.Encoder
            import com.ditchoom.buffer.codec.annotations.Direction
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbEncoder : Encoder<Rgb> {
                override fun encode(buffer: WriteBuffer, value: Rgb) {
                    buffer.writeUByte(value.r)
                }
            }

            @ProtocolMessage(direction = Direction.DecodeOnly)
            data class DecodeOnlyPoint(
                val x: Int,
                @UseCodec(RgbEncoder::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("direction = DecodeOnly") &&
                result.messages.contains("encode-only"),
            "Expected conflict error. Got: ${result.messages}",
        )
    }

    @Test
    fun `direction EncodeOnly with decode-only field errors`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.annotations.Direction
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbDecoder : Decoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }

            @ProtocolMessage(direction = Direction.EncodeOnly)
            data class EncodeOnlyPoint(
                val x: Int,
                @UseCodec(RgbDecoder::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("direction = EncodeOnly") &&
                result.messages.contains("decode-only"),
            "Expected conflict error. Got: ${result.messages}",
        )
    }

    @Test
    fun `direction DecodeOnly with all bidirectional fields compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.Direction
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage(direction = Direction.DecodeOnly)
            data class ForcedDecodeOnly(val x: Int, val y: Short)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `direction EncodeOnly with all bidirectional fields compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.Direction
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage(direction = Direction.EncodeOnly)
            data class ForcedEncodeOnly(val x: Int, val y: Short)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    // ──────────────────── Direction inference conflicts ────────────────────

    @Test
    fun `mixed decode-only and encode-only fields with Infer errors`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.Encoder
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbDecoder : Decoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }

            object RgbEncoder : Encoder<Rgb> {
                override fun encode(buffer: WriteBuffer, value: Rgb) {
                    buffer.writeUByte(value.r)
                }
            }

            @ProtocolMessage
            data class ConflictPoint(
                @UseCodec(RgbDecoder::class) val colorA: Rgb,
                @UseCodec(RgbEncoder::class) val colorB: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("Cannot infer direction") &&
                result.messages.contains("decode-only") &&
                result.messages.contains("encode-only"),
            "Expected inference conflict error. Got: ${result.messages}",
        )
    }

    @Test
    fun `one decode-only among bidirectional fields infers decode-only`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbDecoder : Decoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }

            @ProtocolMessage
            data class MixedPoint(
                val x: Int,
                val y: Int,
                @UseCodec(RgbDecoder::class) val color: Rgb,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    // ──────────────────── Sealed interface direction cascading ────────────────────

    @Test
    fun `sealed interface with all bidirectional variants compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            sealed interface Command {
                @ProtocolMessage @PacketType(1)
                data class Ping(val id: Int) : Command

                @ProtocolMessage @PacketType(2)
                data class Pong(val id: Int) : Command
            }
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `sealed interface with decode-only variant infers decode-only`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbDecoder : Decoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }

            @ProtocolMessage
            sealed interface ImageCommand {
                @ProtocolMessage @PacketType(1)
                data class Text(val id: Int) : ImageCommand

                @ProtocolMessage @PacketType(2)
                data class Image(val id: Int, @UseCodec(RgbDecoder::class) val color: Rgb) : ImageCommand
            }
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `sealed direction Codec with decode-only variant errors`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.codec.Decoder
            import com.ditchoom.buffer.codec.annotations.Direction
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.UseCodec

            data class Rgb(val r: UByte, val g: UByte, val b: UByte)

            object RgbDecoder : Decoder<Rgb> {
                override fun decode(buffer: ReadBuffer): Rgb =
                    Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }

            @ProtocolMessage(direction = Direction.Codec)
            sealed interface Strict {
                @ProtocolMessage @PacketType(1)
                data class Text(val id: Int) : Strict

                @ProtocolMessage @PacketType(2)
                data class Image(val id: Int, @UseCodec(RgbDecoder::class) val color: Rgb) : Strict
            }
            """,
            )
        val result = compileWithKsp(source)
        assertTrue(
            result.messages.contains("direction = Codec") &&
                result.messages.contains("bidirectional"),
            "Expected sealed direction error. Got: ${result.messages}",
        )
    }
}
