package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Codegen-shape assertions for the `wireSize` override emitted on every generated codec.
 *
 * Each test compiles a small `@ProtocolMessage` source via KSP and greps the generated
 * `.kt` for the exact textual shape of the wireSize body. The shapes are:
 *
 *  - Standard codec (no `@Payload`):
 *      `override fun wireSize(value: T): Int { var _size = 0 ... return _size }`
 *
 *  - Payload-bearing codec (`<@Payload P>`):
 *      `fun <P> wireSize(value: T<P>, sizePayload: (P) -> Int): Int { var _size = 0 ... }`
 *      (no `override`, takes a per-payload size lambda)
 *
 *  - Sealed `@DispatchOn` dispatcher with a payload-bearing variant:
 *      `override fun wireSize(value: Sealed): Int = when (value) {
 *          is NonPayload -> NonPayloadCodec.wireSize(value)
 *          is PayloadVariant<*> -> error("...wireSize(value, payloadSize)...")
 *      }`
 */
class WireSizeCodegenTest {
    @Test
    fun `standard codec emits wireSize override with size accumulator`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class StandardMsg(val id: UShort, val flags: UByte)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        val codec =
            result.generatedSources.singleOrNull { it.contains("object StandardMsgCodec") }
                ?: error("Expected StandardMsgCodec in generated sources:\n${result.generatedSources}")
        assertTrue(
            codec.contains("override fun wireSize(`value`: StandardMsg): Int") ||
                codec.contains("override fun wireSize(value: StandardMsg): Int"),
            "Standard wireSize override signature missing. Generated:\n$codec",
        )
        assertTrue(codec.contains("var _size = 0"), "Expected `var _size = 0` accumulator init. Generated:\n$codec")
        assertTrue(codec.contains("_size +="), "Expected `_size +=` accumulation lines. Generated:\n$codec")
        assertTrue(codec.contains("return _size"), "Expected `return _size` terminator. Generated:\n$codec")
    }

    @Test
    fun `payload codec emits generic wireSize with payload size lambda`() {
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
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        val codec =
            result.generatedSources.singleOrNull { it.contains("object PayloadMsgCodec") }
                ?: error("Expected PayloadMsgCodec in generated sources:\n${result.generatedSources}")
        // Payload-bearing codec: top-level `fun <P> wireSize(...)`, NOT an `override`.
        // Signature includes a per-payload `sizeData: (P) -> Int` lambda.
        assertTrue(
            codec.contains("fun <P> wireSize(") &&
                codec.contains("sizeData: (P) -> Int") &&
                codec.contains("PayloadMsg<P>"),
            "Expected `fun <P> wireSize(value: PayloadMsg<P>, sizeData: (P) -> Int): Int`. Generated:\n$codec",
        )
        // The body still uses the size accumulator pattern.
        assertTrue(codec.contains("var _size = 0"), "Expected `var _size = 0` accumulator. Generated:\n$codec")
        assertTrue(codec.contains("_size +="), "Expected `_size +=` accumulation. Generated:\n$codec")
        assertTrue(codec.contains("return _size"), "Expected `return _size` terminator. Generated:\n$codec")
        // Payload field contributes via the user-supplied size lambda.
        assertTrue(
            codec.contains("sizeData(value.data)"),
            "Expected payload-size delegation via `sizeData(value.data)`. Generated:\n$codec",
        )
    }

    @Test
    fun `sealed dispatcher emits when-branch wireSize with error for payload variants`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class HeaderTag(val raw: UByte) {
                @DispatchValue
                val typeId: Int get() = raw.toInt()
            }

            @DispatchOn(HeaderTag::class)
            @ProtocolMessage
            sealed interface Frame {
                @PacketType(value = 1, wire = 0x01)
                @ProtocolMessage
                data class Plain(val x: UShort) : Frame

                @PacketType(value = 2, wire = 0x02)
                @ProtocolMessage
                data class WithPayload<@Payload P>(
                    val len: UShort,
                    @LengthFrom("len") val data: P,
                ) : Frame
            }
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        val codec =
            result.generatedSources.singleOrNull { it.contains("object FrameCodec") }
                ?: error("Expected FrameCodec dispatcher in generated sources:\n${result.generatedSources}")

        // Dispatcher emits a `when (value) { is X -> ... }` wireSize body.
        assertTrue(
            codec.contains("override fun wireSize(`value`: Frame): Int = when (value) {") ||
                codec.contains("override fun wireSize(value: Frame): Int = when (value) {"),
            "Expected `override fun wireSize(value: Frame): Int = when (value) {`. Generated:\n$codec",
        )
        // Non-payload variant delegates to the variant codec's wireSize. The dispatcher
        // also adds the discriminator's own wireSize, so the line shape is
        // `is Frame.Plain -> <DiscCodec>.wireSize(...) + FramePlainCodec.wireSize(value)`.
        assertTrue(
            codec.contains("is Frame.Plain ->") &&
                codec.contains("FramePlainCodec.wireSize(value)"),
            "Expected non-payload variant `is Frame.Plain -> ... FramePlainCodec.wireSize(value)`. Generated:\n$codec",
        )
        // Payload variant emits an error pointing at the payload-overload.
        assertTrue(
            codec.contains("is Frame.WithPayload<*> -> error("),
            "Expected `is Frame.WithPayload<*> -> error(...)` branch. Generated:\n$codec",
        )
        assertTrue(
            codec.contains("FrameWithPayloadCodec.wireSize(value, payloadSize)"),
            "Expected error message to reference `FrameWithPayloadCodec.wireSize(value, payloadSize)`. Generated:\n$codec",
        )
    }

    @Test
    fun `payload codec emits SizeKey and wireSizeFromContext alongside encodeFromContext`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class CtxPayloadMsg<@Payload P>(
                val id: UShort,
                @LengthPrefixed val data: P,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        val codec =
            result.generatedSources.singleOrNull { it.contains("object CtxPayloadMsgCodec") }
                ?: error("Expected CtxPayloadMsgCodec in generated sources:\n${result.generatedSources}")
        // SizeKey: data object alongside DecodeKey/EncodeKey, of type (Any?) -> Int
        assertTrue(
            codec.contains("data object DataSizeKey"),
            "Expected `data object DataSizeKey` per-payload-field SizeKey. Generated:\n$codec",
        )
        // wireSizeFromContext: reads SizeKey from context, delegates to wireSize(value, lambda)
        assertTrue(
            codec.contains("public fun wireSizeFromContext(") ||
                codec.contains("fun wireSizeFromContext("),
            "Expected `wireSizeFromContext(value, context): Int`. Generated:\n$codec",
        )
        assertTrue(
            codec.contains("CtxPayloadMsgCodec.DataSizeKey") ||
                codec.contains("DataSizeKey]"),
            "Expected wireSizeFromContext body to read DataSizeKey. Generated:\n$codec",
        )
        assertTrue(
            codec.contains("return wireSize(value,"),
            "Expected wireSizeFromContext to delegate `return wireSize(value, ...)`. Generated:\n$codec",
        )
    }

    @Test
    fun `sealed dispatcher encodeFromContext uses inline-prefix via wireSizeFromContext for payload variants`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class FramedTag(val raw: UByte) {
                @DispatchValue
                val typeId: Int get() = raw.toInt()
            }

            @DispatchOn(FramedTag::class, bodyLength = LengthPrefix.Varint, bodyLengthMaxBytes = 4)
            @ProtocolMessage
            sealed interface Framed {
                @PacketType(value = 1, wire = 0x01)
                @ProtocolMessage
                data class WithPayload<@Payload P>(
                    val len: UShort,
                    @LengthFrom("len") val data: P,
                ) : Framed
            }
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        val codec =
            result.generatedSources.singleOrNull { it.contains("object FramedCodec") }
                ?: error("Expected FramedCodec in generated sources:\n${result.generatedSources}")
        // The encodeFromContext path for payload variants should use inline-prefix (val
        // _len_body = ... ; writeVariableByteInteger), NOT the scratch-buffer helper.
        // We assert by checking the wireSizeFromContext call is woven into the encode
        // statement chain — its presence proves the inline-prefix path took over from
        // writeVariableByteIntegerLengthPrefixed at this site.
        assertTrue(
            codec.contains("FramedWithPayloadCodec.wireSizeFromContext(value, context)"),
            "Expected sealed dispatcher's encodeFromContext payload variant to call " +
                "`wireSizeFromContext(value, context)` for inline-prefix sizing. Generated:\n$codec",
        )
        // The generated dispatcher should NOT call the scratch helper for the
        // encodeFromContext payload-variant path. Count actual call sites — the trailing
        // `(` distinguishes a call from the `import com.ditchoom.buffer.write…Prefixed`
        // line, which also contains the substring `buffer.writeVariableByteIntegerLengthPrefixed`.
        // The typed-lambda dispatcher entrypoint still uses scratch since size lambdas
        // aren't available there, so allow at most 1 call site.
        val scratchCallSites = "buffer\\.writeVariableByteIntegerLengthPrefixed\\(".toRegex().findAll(codec).count()
        assertTrue(
            scratchCallSites <= 1,
            "Expected encodeFromContext payload variant to bypass scratch helper; found " +
                "$scratchCallSites `buffer.writeVariableByteIntegerLengthPrefixed(` call sites " +
                "(max 1 allowed for the typed-lambda entrypoint). Generated:\n$codec",
        )
    }

    @Test
    fun `variant codec with DiscriminatorField emits encodeBody and wireSizeBody that skip the discriminator`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class Tag(val raw: UByte) {
                @DispatchValue
                val typeId: Int get() = (raw.toInt() shr 4) and 0x0F
            }

            @DispatchOn(Tag::class)
            @ProtocolMessage
            sealed interface Frame {
                @PacketType(value = 1, wire = 0x10)
                @ProtocolMessage
                data class WithHeader(
                    val header: Tag,
                    @LengthPrefixed val name: String,
                ) : Frame
            }
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        val codec =
            result.generatedSources.singleOrNull { it.contains("object FrameWithHeaderCodec") }
                ?: error("Expected FrameWithHeaderCodec in generated sources:\n${result.generatedSources}")

        // Standard encode + wireSize INCLUDE the discriminator (header) field
        // — the dispatcher hands them off when the variant self-encodes.
        assertTrue(
            codec.contains("TagCodec.encode(buffer, value.header"),
            "Standard encode must write the discriminator field. Generated:\n$codec",
        )
        assertTrue(
            codec.contains("TagCodec.wireSize(value.header)"),
            "Standard wireSize must include the discriminator size. Generated:\n$codec",
        )

        // encodeBody / wireSizeBody EXCLUDE the discriminator — they let the
        // caller (the wrapping ControlPacket) write byte1 + length-prefix itself.
        assertTrue(
            codec.contains("public fun encodeBody(") || codec.contains("fun encodeBody("),
            "Expected encodeBody(buffer, value, context) entry point. Generated:\n$codec",
        )
        assertTrue(
            codec.contains("public fun wireSizeBody(") || codec.contains("fun wireSizeBody("),
            "Expected wireSizeBody(value): Int entry point. Generated:\n$codec",
        )
        // The body variants must NOT touch the header field. Slice the wireSizeBody
        // function body only — bounded by the next top-level `fun `/`override fun `
        // so we don't bleed into the sibling wireSize that DOES include header.
        val bodyDeclStart = codec.indexOf("fun wireSizeBody(")
        check(bodyDeclStart >= 0)
        val afterDecl = codec.substring(bodyDeclStart)
        val nextFunIdx = afterDecl.indexOf("\n    fun ", startIndex = 1)
        val nextOverrideIdx = afterDecl.indexOf("\n    override fun ", startIndex = 1)
        val bodyEnd =
            listOf(nextFunIdx, nextOverrideIdx, afterDecl.length)
                .filter { it > 0 }
                .min()
        val bodySlice = afterDecl.substring(0, bodyEnd)
        assertTrue(
            "TagCodec.wireSize(value.header)" !in bodySlice,
            "wireSizeBody must skip the discriminator field. Body slice:\n$bodySlice",
        )
    }

    @Test
    fun `custom variable-size field without wireSizeFunction reports clean KSP error at field`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @Target(AnnotationTarget.VALUE_PARAMETER)
            @Retention(AnnotationRetention.BINARY)
            annotation class VbiNoSize

            @ProtocolMessage
            data class BadCustom(val id: UByte, @VbiNoSize val len: Int)
            """,
            )
        // Provider returns variable-length custom (fixedSize=-1) AND no wireSizeFunction.
        // FieldAnalyzer should reject this via KSPLogger.error pointing at the offending
        // field, not let it propagate as an uncaught processor exception during code gen.
        val provider =
            object : com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider {
                override val annotationFqn = "test.VbiNoSize"

                override fun describe(
                    context: com.ditchoom.buffer.codec.processor.spi.FieldContext,
                ): com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor =
                    com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor(
                        readFunction =
                            com.ditchoom.buffer.codec.processor.spi.FunctionRef(
                                "com.ditchoom.buffer",
                                "readVariableByteInteger",
                            ),
                        writeFunction =
                            com.ditchoom.buffer.codec.processor.spi.FunctionRef(
                                "com.ditchoom.buffer",
                                "writeVariableByteInteger",
                            ),
                        fixedSize = -1,
                    )
            }
        val result = compileWithKspAndCustomProviders(source, providers = listOf(provider))
        assertTrue(
            result.exitCode != KotlinCompilation.ExitCode.OK,
            "Expected compile failure but got OK:\n${result.messages}",
        )
        // The error must mention the field name and the wireSizeFunction option so the
        // user knows exactly what to add to their CustomFieldDescriptor.
        assertTrue(
            "len" in result.messages && "wireSizeFunction" in result.messages,
            "Expected error to mention field 'len' and 'wireSizeFunction'. Got:\n${result.messages}",
        )
        // The error must be reported via KSPLogger so the build output points at the
        // source position; an uncaught processor exception would prefix with
        // 'java.lang.IllegalStateException' / similar instead of the KSP `e: [ksp]` shape.
        assertTrue(
            "[ksp]" in result.messages || "e: " in result.messages,
            "Expected KSP-routed error (`[ksp]` or `e:` prefix), not raw exception. Got:\n${result.messages}",
        )
    }
}
