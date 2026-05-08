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
 * Phase I.1 step 3 — `@UseCodec` validator coverage for the bare-scalar
 * shape (no framing annotation). The bare shape unblocks user-supplied
 * length codecs like `MqttRemainingLengthCodec`. Slice 10a's
 * `@RemainingBytes @UseCodec val: P` shape and the deferred
 * `@LengthPrefixed @UseCodec` / `@LengthFrom @UseCodec` shapes still produce
 * the same diagnostics as before.
 */
class UseCodecScalarValidatorTest {
    @Test
    fun acceptsBareUseCodecOnScalarFieldWithMatchingCodec() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object UIntPassthroughCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithUseCodecScalar(
                    @UseCodec(UIntPassthroughCodec::class) val length: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@UseCodec"),
            "no @UseCodec diagnostic should fire on a bare scalar shape. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsBareUseCodecOnScalarWithMismatchedCodecType() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object IntCodec : Codec<Int> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Int = buffer.readInt()
                    override fun encode(buffer: WriteBuffer, value: Int, context: EncodeContext) {
                        buffer.writeInt(value)
                    }
                    override fun wireSize(value: Int, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithMismatchedCodec(
                    @UseCodec(IntCodec::class) val length: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("does not implement"),
            "diagnostic should report the Codec<T> type mismatch. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("HeaderWithMismatchedCodec.length"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsBareUseCodecOnScalarWhenTargetIsNotObject() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                class UIntClassCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithClassCodec(
                    @UseCodec(UIntClassCodec::class) val length: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("not a Kotlin `object` declaration"),
            "diagnostic should reject the non-object target. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsLengthPrefixedUseCodecOnNonListNonPayloadNonStringField() {
        // Phase J.M.5 slice 15a / J.M.7.b — `@LengthPrefixed @UseCodec`
        // accepts three shapes: `List<@ProtocolMessage E>` (slice 11),
        // `T : Payload` (slice 15a), and `kotlin.String` with a
        // `Codec<String>` (J.M.7.b). A non-List, non-Payload, non-String
        // field type (here: a bare `UInt`) is none of those and gets a
        // focused diagnostic naming all three shapes.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object FixedFourByteUIntCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                data class HeaderWithLengthPrefixedUseCodecScalar(
                    @LengthPrefixed @UseCodec(FixedFourByteUIntCodec::class) val n: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("`kotlin.collections.List<E>` (slice 11 list shape)") &&
                result.messages.contains("`com.ditchoom.buffer.codec.Payload` (slice 15a scalar shape)") &&
                result.messages.contains("`kotlin.String` (J.M.7.b user-charset shape)"),
            "@LengthPrefixed @UseCodec on a non-List, non-Payload, non-String field should report " +
                "the tri-shape diagnostic naming List<E>, Payload, and String. Messages:\n" +
                result.messages,
        )
    }

    @Test
    fun acceptsLengthPrefixedUseCodecOnPayloadScalar() {
        // Phase J.M.5 slice 15a — `@LengthPrefixed @UseCodec(C::class) val: T`
        // where `T : Payload` and `C` is `Codec<T>` (not a
        // BoundingLengthCodec — the prefix is owned by the framework).
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
                data class HeaderWithLengthPrefixedPayload(
                    @LengthPrefixed @UseCodec(BlobCodec::class) val data: Blob,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@LengthPrefixed @UseCodec") &&
                result.messages.contains("error"),
            "no validator diagnostic should fire on the new Payload scalar shape. Messages:\n" +
                result.messages,
        )
    }

    @Test
    fun acceptsLengthPrefixedUseCodecOnStringField() {
        // J.M.7.b — `@LengthPrefixed @UseCodec(C::class) val: String`
        // where `C` is a Kotlin `object` implementing `Codec<String>`
        // (built-in `AsciiStringCodec` or a consumer's per-charset
        // codec). Same wire shape as the Payload variant: prefix +
        // body bytes consumed by the user codec.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object IdentityStringCodec : Codec<String> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): String =
                        buffer.readString(buffer.remaining())
                    override fun encode(buffer: WriteBuffer, value: String, context: EncodeContext) {
                        buffer.writeString(value)
                    }
                    override fun wireSize(value: String, context: EncodeContext): WireSize =
                        WireSize.Exact(value.length)
                }

                @ProtocolMessage
                data class HeaderWithLengthPrefixedString(
                    @LengthPrefixed @UseCodec(IdentityStringCodec::class) val name: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@LengthPrefixed @UseCodec") &&
                result.messages.contains("error"),
            "no validator diagnostic should fire on the J.M.7.b String shape. Messages:\n" +
                result.messages,
        )
    }

    @Test
    fun rejectsLengthPrefixedUseCodecOnStringWithMismatchedCodec() {
        // The codec must implement `Codec<String>` for a String-typed
        // field. A `Codec<Payload>`-style codec produces a mismatch.
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
                data class HeaderWithStringCodecMismatch(
                    @LengthPrefixed @UseCodec(BlobCodec::class) val name: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("does not implement") && result.messages.contains("Codec<String>"),
            "String-typed field codec mismatch should be reported. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsLengthPrefixedUseCodecOnPayloadScalarWithMismatchedCodec() {
        // The codec must implement `Codec<T>` for the field's declared
        // Payload type. A `Codec<OtherPayload>` references on `T : Payload`
        // produces a focused mismatch diagnostic.
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

                @JvmInline
                value class OtherBlob(val bytes: ByteArray) : Payload

                object OtherBlobCodec : Codec<OtherBlob> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): OtherBlob =
                        OtherBlob(buffer.readByteArray(buffer.remaining()))
                    override fun encode(buffer: WriteBuffer, value: OtherBlob, context: EncodeContext) {
                        buffer.writeBytes(value.bytes)
                    }
                    override fun wireSize(value: OtherBlob, context: EncodeContext): WireSize =
                        WireSize.Exact(value.bytes.size)
                }

                @ProtocolMessage
                data class HeaderWithMismatch(
                    @LengthPrefixed @UseCodec(OtherBlobCodec::class) val data: Blob,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("does not implement") && result.messages.contains("Codec<Blob>"),
            "Payload scalar codec mismatch should be reported. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsLengthPrefixedUseCodecOnProtocolMessageList() {
        // Phase I.1 step 11 acceptance — `@LengthPrefixed @UseCodec(C::class)
        // val xs: List<E>` where `C : BoundingLengthCodec<UInt>` and `E` is a
        // `@ProtocolMessage data class`. Drives the v5 property-list shape.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object FourByteLengthCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                data class Element(val raw: Int)

                @ProtocolMessage
                data class Bag(
                    @LengthPrefixed @UseCodec(FourByteLengthCodec::class) val items: List<Element>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@UseCodec"),
            "no @UseCodec diagnostic should fire on the new List shape. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsLengthPrefixedUseCodecWithNonBoundingCodec() {
        // The shape requires `BoundingLengthCodec<UInt>` so `applyBound` is
        // available; a plain `Codec<UInt>` produces a focused diagnostic.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object PlainUIntCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class Element(val raw: Int)

                @ProtocolMessage
                data class Bag(
                    @LengthPrefixed @UseCodec(PlainUIntCodec::class) val items: List<Element>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("does not implement") &&
                result.messages.contains("BoundingLengthCodec<UInt>"),
            "non-bounding codec should be rejected with a focused diagnostic. Messages:\n" +
                result.messages,
        )
    }

    @Test
    fun rejectsLengthPrefixedUseCodecWithNonProtocolMessageElement() {
        // The element type must be a `@ProtocolMessage data class` so the
        // emitter can call `<E>Codec.decode(...)` per element.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object FourByteLengthCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                data class Bag(
                    @LengthPrefixed @UseCodec(FourByteLengthCodec::class) val items: List<Int>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("not a `@ProtocolMessage data class`"),
            "non-@ProtocolMessage element should be rejected with a focused diagnostic. " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsLengthPrefixedUseCodecOnSealedParentList() {
        // Phase J.M.5 widening — `@LengthPrefixed @UseCodec(C) val xs: List<E>`
        // also accepts `E` being a `@ProtocolMessage` sealed parent with
        // `@DispatchOn`. The MQTT v5 property bag is exactly this shape:
        // a polymorphic list of typed properties dispatched on the property
        // identifier byte. The dispatcher's generated codec is a singleton
        // object whose `decode`/`encode` signatures match what the
        // sealed-element encode emit calls.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object FourByteLengthCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @JvmInline
                @ProtocolMessage
                value class PropId(val raw: UByte) {
                    @DispatchValue val id: Int get() = raw.toInt()
                }

                @DispatchOn(PropId::class)
                @ProtocolMessage
                sealed interface Property {
                    @PacketType(value = 0x02)
                    @ProtocolMessage
                    data class Expiry(val id: PropId = PropId(0x02u), val seconds: UInt) : Property

                    @PacketType(value = 0x03)
                    @ProtocolMessage
                    data class ContentType(val id: PropId = PropId(0x03u), val raw: UByte) : Property
                }

                @ProtocolMessage
                data class Bag(
                    @LengthPrefixed @UseCodec(FourByteLengthCodec::class) val items: List<Property>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@UseCodec"),
            "no @UseCodec diagnostic should fire on a sealed-parent element. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsLengthPrefixedUseCodecOnPayloadGenericSealedParentElement() {
        // Phase J.M.5 — sealed-parent elements with `<P : Payload>` are
        // rejected because their generated codec emits as a generic class
        // (slice 10d detection rule), not a singleton object. The emitter
        // calls `<E>Codec.decode(...)` directly, which requires the
        // singleton form.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object FourByteLengthCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @JvmInline
                @ProtocolMessage
                value class Tag(val raw: UByte) {
                    @DispatchValue val id: Int get() = raw.toInt()
                }

                @DispatchOn(Tag::class)
                @ProtocolMessage
                sealed interface PayloadCarrier<out P : Payload> {
                    @PacketType(value = 1)
                    @ProtocolMessage
                    data class Body<P : Payload>(
                        val tag: Tag = Tag(0x01u),
                        @RemainingBytes val body: P,
                    ) : PayloadCarrier<P>
                }

                @ProtocolMessage
                data class Bag(
                    @LengthPrefixed @UseCodec(FourByteLengthCodec::class) val items: List<PayloadCarrier<*>>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("`<P : Payload>` type parameter") &&
                result.messages.contains("singleton object"),
            "payload-generic sealed parent should be rejected with a focused diagnostic. " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsLengthFromUseCodecAsDeferred() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object UIntPassthroughCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithLengthFromUseCodec(
                    val length: Int,
                    @LengthFrom("length") @UseCodec(UIntPassthroughCodec::class) val payload: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("not yet supported"),
            "@LengthFrom @UseCodec should still produce the deferred diagnostic. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsRemainingBytesUseCodecOnNonPayloadScalar() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object UIntPassthroughCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithRemainingBytesScalar(
                    @RemainingBytes @UseCodec(UIntPassthroughCodec::class) val n: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("requires the bound") &&
                result.messages.contains("Payload"),
            "@RemainingBytes @UseCodec on a non-Payload field should still be rejected. " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsBareUseCodecOnValueClassScalarField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                @JvmInline
                value class FrameLength(val raw: UInt)

                object FrameLengthCodec : Codec<FrameLength> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): FrameLength =
                        FrameLength(buffer.readInt().toUInt())
                    override fun encode(buffer: WriteBuffer, value: FrameLength, context: EncodeContext) {
                        buffer.writeInt(value.raw.toInt())
                    }
                    override fun wireSize(value: FrameLength, context: EncodeContext): WireSize =
                        WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithValueClassScalar(
                    @UseCodec(FrameLengthCodec::class) val length: FrameLength,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@UseCodec"),
            "no @UseCodec diagnostic should fire on a bare value-class shape. Messages:\n${result.messages}",
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
