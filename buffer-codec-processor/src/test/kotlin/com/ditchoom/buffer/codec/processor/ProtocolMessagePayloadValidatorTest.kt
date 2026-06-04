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
 * Compile-time validator coverage for the raw-bytes ban:
 *
 * 1. **Outer rule** (§8): raw-bytes types (`ReadBuffer`, `WriteBuffer`,
 *    `PlatformBuffer`, `ByteArray`, primitive arrays, `ByteBuffer`) cannot
 *    appear in the fields of a `@ProtocolMessage` data class. The walk
 *    recurses through `@JvmInline value class` wrappers and generic type
 *    arguments. Outer-rule diagnostic blames the @ProtocolMessage field.
 *
 * 2. **Transitive Payload rule** (buffer-codec lockdown v1, Change 1):
 *    when the walk encounters a concrete `Payload`-implementing type, it
 *    descends into the Payload's declared properties (recursively through
 *    value-class wrappers and sealed trees) and rejects the same set of
 *    raw-bytes types. Payload-rule diagnostic blames the Payload's
 *    declared property, since the Payload type itself is unsound.
 */
class ProtocolMessagePayloadValidatorTest {
    @Test
    fun firesOnRawReadBufferField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawReadBufferField(val raw: ReadBuffer)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "RawReadBufferField.raw", "com.ditchoom.buffer.ReadBuffer")
    }

    @Test
    fun firesThroughValueClassWrapper() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @JvmInline
                value class ChunkBody(val raw: ReadBuffer)

                @ProtocolMessage
                data class ChunkWithWrappedBuffer(val body: ChunkBody)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "ChunkWithWrappedBuffer.body", "com.ditchoom.buffer.ReadBuffer")
    }

    @Test
    fun firesOnDataClassPayloadWithByteArrayProperty() {
        // Buffer-codec lockdown v1, Change 1: the walk no longer short-circuits
        // at Payload — it descends into the Payload's declared properties and
        // rejects raw-bytes types. A `data class OpaqueBlob(val bytes: ByteArray)
        // : Payload` is structurally unsound (the bytes outlive the codec
        // scope ambiguously), so the violation is the Payload type itself.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes
                import com.ditchoom.buffer.codec.annotations.UseCodec

                data class OpaqueBlob(val bytes: ByteArray) : Payload

                object OpaqueBlobCodec : Codec<OpaqueBlob> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): OpaqueBlob =
                        OpaqueBlob(buffer.readByteArray(buffer.remaining()))
                    override fun encode(buffer: WriteBuffer, value: OpaqueBlob, context: EncodeContext) {
                        buffer.writeBytes(value.bytes)
                    }
                    override fun wireSize(value: OpaqueBlob, context: EncodeContext): WireSize =
                        WireSize.Exact(value.bytes.size)
                }

                @ProtocolMessage
                data class ChunkWithPayloadField(
                    @RemainingBytes @UseCodec(OpaqueBlobCodec::class) val body: OpaqueBlob,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsPayloadShapeError(result, "OpaqueBlob.bytes", "kotlin.ByteArray")
    }

    @Test
    fun firesOnByteArrayField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawByteArrayField(val bytes: ByteArray)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "RawByteArrayField.bytes", "kotlin.ByteArray")
    }

    @Test
    fun firesOnGenericArgument() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class ListOfRaw(val frames: List<ByteArray>)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "ListOfRaw.frames", "kotlin.ByteArray")
    }

    @Test
    fun acceptsBenignFields() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class ScalarOnly(
                    val id: UInt,
                    @LengthPrefixed val name: String,
                    val flag: Boolean,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(result.messages.contains("Section 8"), "no diagnostic should fire on scalar fields")
    }

    @Test
    fun firesOnRawWriteBufferField() {
        // Same §8/D1 ban applies to WriteBuffer.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawWriteBufferField(val raw: WriteBuffer)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "RawWriteBufferField.raw", "com.ditchoom.buffer.WriteBuffer")
    }

    @Test
    fun firesOnRawPlatformBufferField() {
        // Same §8/D1 ban applies to PlatformBuffer.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.PlatformBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawPlatformBufferField(val raw: PlatformBuffer)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(
            result,
            "RawPlatformBufferField.raw",
            "com.ditchoom.buffer.PlatformBuffer",
        )
    }

    @Test
    fun firesOnValueClassPayloadWithByteArrayInner() {
        // Buffer-codec lockdown v1, Change 1: even a value-class Payload
        // cannot wrap raw bytes — the bytes still escape the codec scope
        // unsoundly. Was previously the documented migration target; is
        // now itself a rejection case. Migration moves further: wrap the
        // bytes in a domain type (Bitmap via native handle), or step
        // outside Payload via a hand-written Codec<NonPayloadType>.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                @JvmInline
                value class Blob(val bytes: ByteArray) : Payload

                object BlobCodec : Codec<Blob> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Blob =
                        Blob(buffer.readByteArray(buffer.remaining()))
                    override fun encode(buffer: WriteBuffer, value: Blob, context: EncodeContext) {
                        buffer.writeBytes(value.bytes)
                    }
                    override fun wireSize(value: Blob, context: EncodeContext): WireSize =
                        WireSize.Exact(value.bytes.size)
                }

                @ProtocolMessage
                data class FrameWithBlob(
                    @LengthPrefixed @UseCodec(BlobCodec::class) val data: Blob,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsPayloadShapeError(result, "Blob.bytes", "kotlin.ByteArray")
    }

    @Test
    fun diagnosticReferencesCanonicalEscapePrimitives() {
        // The diagnostic must explain the rule and point at the canonical
        // migration targets — both the typed-value Payload path (string,
        // scalars, native handle) and the step-outside-Payload primitives
        // (copyToByteArray, factory.allocate().write) for the raw-bytes case.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class StillRaw(val bytes: ByteArray)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("raw-bytes type") && result.messages.contains("forbidden"),
            "diagnostic should explain that raw-bytes types are forbidden. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("Payload") && result.messages.contains("@UseCodec"),
            "diagnostic should point at the Payload + @UseCodec composition. " +
                "Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("copyToByteArray") &&
                result.messages.contains("factory.allocate"),
            "diagnostic should name the step-outside-Payload primitives for the raw-bytes " +
                "consumer case. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnNestedValueClassReachingPayloadWithByteArray() {
        // Buffer-codec lockdown v1, Change 1: the Payload-shape walk runs
        // wherever the outer walk reaches a Payload type — including through
        // an outer value-class wrapper. WrappedPayload → OpaqueBlob (Payload)
        // → bytes: ByteArray fires the Payload-flavored diagnostic against
        // `OpaqueBlob.bytes` (the structural offender), not the outer field.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                data class OpaqueBlob(val bytes: ByteArray) : Payload

                @JvmInline
                value class WrappedPayload(val payload: OpaqueBlob)

                @ProtocolMessage
                data class ChunkWithWrappedPayload(val body: WrappedPayload)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsPayloadShapeError(result, "OpaqueBlob.bytes", "kotlin.ByteArray")
    }

    // ========================================================================
    // Change 1 (buffer-codec lockdown v1) — Payload-shape rule positive cases
    // ========================================================================

    @Test
    fun acceptsDataClassPayloadOfBenignScalars() {
        // Self-contained typed-value Payload — no raw-bytes anywhere in the
        // declared shape — passes.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                data class Dimensions(val width: UShort, val height: UShort) : Payload

                object DimensionsCodec : Codec<Dimensions> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Dimensions =
                        Dimensions(buffer.readUShort(), buffer.readUShort())
                    override fun encode(buffer: WriteBuffer, value: Dimensions, context: EncodeContext) {
                        buffer.writeUShort(value.width); buffer.writeUShort(value.height)
                    }
                    override fun wireSize(value: Dimensions, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithDimensions(
                    @LengthPrefixed @UseCodec(DimensionsCodec::class) val dim: Dimensions,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("raw-bytes type") || result.messages.contains("Payload types must not"),
            "scalar-only Payload should pass. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsDataClassPayloadCarryingString() {
        // `kotlin.String` is a typed value, not a raw-bytes type — Payload OK.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                data class TextPayload(val text: String) : Payload

                object TextCodec : Codec<TextPayload> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): TextPayload =
                        TextPayload(buffer.readString(buffer.remaining()))
                    override fun encode(buffer: WriteBuffer, value: TextPayload, context: EncodeContext) {
                        buffer.writeString(value.text)
                    }
                    override fun wireSize(value: TextPayload, context: EncodeContext): WireSize =
                        WireSize.Exact(value.text.length)
                }

                @ProtocolMessage
                data class MessageWithText(
                    @LengthPrefixed @UseCodec(TextCodec::class) val text: TextPayload,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("raw-bytes type") || result.messages.contains("Payload types must not"),
            "String-carrying Payload should pass. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnSealedPayloadWithOneBadVariant() {
        // Sealed Payload tree: every variant must satisfy the rule. One
        // variant carrying ByteArray fails the whole tree at that variant.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                sealed interface Event : Payload {
                    data class Tick(val seq: UInt) : Event
                    data class Snapshot(val image: ByteArray) : Event
                }

                object EventCodec : Codec<Event> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Event = Event.Tick(0u)
                    override fun encode(buffer: WriteBuffer, value: Event, context: EncodeContext) {}
                    override fun wireSize(value: Event, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class Stream(@LengthPrefixed @UseCodec(EventCodec::class) val event: Event)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsPayloadShapeError(result, "Event.Snapshot.image", "kotlin.ByteArray")
    }

    @Test
    fun firesOnNestedNonValueWrapperReachingForbiddenByteArray() {
        // Plan template case: `data class Outer(val inner: HasBuffer) : Payload`
        // where HasBuffer wraps a forbidden type. The walk descends through the
        // value-class wrapper inside the Payload and rejects the inner bytes.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @JvmInline
                value class HasBuffer(val bytes: ByteArray)

                data class Outer(val inner: HasBuffer) : Payload

                @ProtocolMessage
                data class Frame(val payload: Outer)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsPayloadShapeError(result, "Outer.inner", "kotlin.ByteArray")
    }

    @Test
    fun firesOnPayloadCarryingIntArray() {
        // Primitive arrays (extended FORBIDDEN_TYPES) are rejected the same
        // way as ByteArray.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                data class IntPayload(val values: IntArray) : Payload

                @ProtocolMessage
                data class FrameWithInts(val payload: IntPayload)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsPayloadShapeError(result, "IntPayload.values", "kotlin.IntArray")
    }

    private fun assertContainsRawBytesError(
        result: JvmCompilationResult,
        ownerDotField: String,
        forbiddenType: String,
    ) {
        assertTrue(
            result.messages.contains("@ProtocolMessage field test.$ownerDotField"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains(forbiddenType),
            "diagnostic should name the forbidden type $forbiddenType. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("ownership ambiguity"),
            "diagnostic should explain the ownership-ambiguity rationale. Messages:\n${result.messages}",
        )
    }

    private fun assertContainsPayloadShapeError(
        result: JvmCompilationResult,
        payloadDotProperty: String,
        forbiddenType: String,
    ) {
        assertTrue(
            result.messages.contains("Payload types must not embed raw buffer or array types"),
            "diagnostic should fire the Payload-shape rule. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("test.$payloadDotProperty: $forbiddenType"),
            "diagnostic should pin the offending property `test.$payloadDotProperty: $forbiddenType`. " +
                "Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("copyToByteArray") || result.messages.contains("factory.allocate"),
            "diagnostic should point at the canonical escape primitives. Messages:\n${result.messages}",
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
