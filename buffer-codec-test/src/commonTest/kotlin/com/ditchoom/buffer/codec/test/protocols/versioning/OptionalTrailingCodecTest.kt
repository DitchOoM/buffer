package com.ditchoom.buffer.codec.test.protocols.versioning

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * End-to-end wire behaviour of `@SinceVersion` trailing fields, in both directions.
 *
 * A passing round-trip proves neither direction on its own — encode and decode can agree with
 * each other while both disagreeing with a peer built at a different revision. So each direction
 * is tested against bytes produced *independently* of the decoder under test:
 *
 *  - **old producer → new reader**: frames encoded by [RegisterV1] (and every truncation of a
 *    full v3 frame) fed to `RegisterV3Codec`.
 *  - **new producer → old reader**: a full v3 frame fed to `RegisterV1Codec`, which must read
 *    its three fields and leave the rest alone.
 */
class OptionalTrailingCodecTest {
    // ---- old producer -> new reader ---------------------------------------

    @Test
    fun `a v1 frame decodes on the v3 reader with defaults`() {
        val v1 = RegisterV1(hostname = "alpha", pid = 4242, secure = true)
        val wire = encode { buffer -> RegisterV1Codec.encode(buffer, v1, EncodeContext.Empty) }

        val decoded = decode(wire) { RegisterV3Codec.decode(it, DecodeContext.Empty) }

        assertEquals("alpha", decoded.hostname)
        assertEquals(4242, decoded.pid)
        assertEquals(true, decoded.secure)
        // The three fields the v1 producer never heard of, at their declared Kotlin defaults.
        assertEquals(RegisterV3.DEFAULT_RETRIES, decoded.retries)
        assertEquals(BuildNumber(0), decoded.build)
        assertEquals(RegisterMode.Standard, decoded.mode)
    }

    @Test
    fun `the v1 and v3 encoders agree on the bytes they share`() {
        // Guards the premise the previous test rests on: if the shared prefix ever diverged, a
        // v1 frame could still decode "successfully" on the v3 reader while meaning something
        // else. Compare the v1 frame against the v3 frame's leading bytes.
        val v1 = RegisterV1(hostname = "alpha", pid = 4242, secure = true)
        val v3 = RegisterV3(hostname = "alpha", pid = 4242, secure = true)
        val v1Wire = encode { RegisterV1Codec.encode(it, v1, EncodeContext.Empty) }
        val v3Wire = encode { RegisterV3Codec.encode(it, v3, EncodeContext.Empty) }

        assertTrue(v3Wire.size > v1Wire.size, "v3 must write the optional fields: ${v3Wire.size}")
        assertContentEquals(v1Wire, v3Wire.copyOfRange(0, v1Wire.size))
    }

    @Test
    fun `every truncation at an optional-field boundary decodes with the remaining defaults`() {
        val full =
            RegisterV3(
                hostname = "alpha",
                pid = 4242,
                secure = true,
                retries = 9,
                build = BuildNumber(1234),
                mode = RegisterMode.Restricted,
            )
        val wire = encode { RegisterV3Codec.encode(it, full, EncodeContext.Empty) }

        // Boundaries, from the tail: mode is a 1-byte varint, build 4 bytes, retries 4 bytes.
        val afterRetriesAndBuild = wire.size - 1
        val afterRetries = afterRetriesAndBuild - 4
        val required = afterRetries - 4

        decode(wire.copyOfRange(0, afterRetriesAndBuild)) {
            RegisterV3Codec.decode(it, DecodeContext.Empty)
        }.let {
            assertEquals(9, it.retries)
            assertEquals(BuildNumber(1234), it.build)
            assertEquals(RegisterMode.Standard, it.mode, "mode absent -> default")
        }

        decode(wire.copyOfRange(0, afterRetries)) {
            RegisterV3Codec.decode(it, DecodeContext.Empty)
        }.let {
            assertEquals(9, it.retries)
            assertEquals(BuildNumber(0), it.build, "build absent -> default")
            assertEquals(RegisterMode.Standard, it.mode)
        }

        decode(wire.copyOfRange(0, required)) {
            RegisterV3Codec.decode(it, DecodeContext.Empty)
        }.let {
            assertEquals(RegisterV3.DEFAULT_RETRIES, it.retries, "retries absent -> default")
            assertEquals(BuildNumber(0), it.build)
            assertEquals(RegisterMode.Standard, it.mode)
        }
    }

    @Test
    fun `truncating into a required field still fails loudly`() {
        // Absent-tolerance must not become corruption-tolerance: a frame that stops PART WAY
        // through a required field is malformed, not old, and must still throw. Without this the
        // feature would silently convert every short read into a default-filled value.
        val v3 = RegisterV3(hostname = "alpha", pid = 4242, secure = true)
        val wire = encode { RegisterV3Codec.encode(it, v3, EncodeContext.Empty) }
        val required = wire.size - 9 // strip the three optional fields
        for (cut in 1 until required) {
            assertFails("a frame cut inside the required prefix (at $cut) must not decode") {
                decode(wire.copyOfRange(0, cut)) { RegisterV3Codec.decode(it, DecodeContext.Empty) }
            }
        }
    }

    @Test
    fun `a partially present optional field fails rather than silently defaulting`() {
        // `retries` is 4 bytes. A frame carrying 1..3 of them is truncated mid-field: the guard
        // passes only at >= 4, so 1..3 bytes must fall to the "absent" branch and then leave
        // trailing bytes unconsumed — or throw. What it must NOT do is read a short int.
        val full = RegisterV3(hostname = "a", pid = 1, secure = false, retries = 0x01020304)
        val wire = encode { RegisterV3Codec.encode(it, full, EncodeContext.Empty) }
        val required = wire.size - 9
        for (extra in 1..3) {
            val decoded =
                decode(wire.copyOfRange(0, required + extra)) {
                    RegisterV3Codec.decode(it, DecodeContext.Empty)
                }
            assertEquals(
                RegisterV3.DEFAULT_RETRIES,
                decoded.retries,
                "$extra trailing byte(s) is fewer than retries needs; it must read as absent",
            )
        }
    }

    // ---- new producer -> old reader ----------------------------------------

    @Test
    fun `a v3 frame decodes on the v1 reader which ignores the trailing bytes`() {
        val v3 =
            RegisterV3(
                hostname = "alpha",
                pid = 4242,
                secure = true,
                retries = 9,
                build = BuildNumber(1234),
                mode = RegisterMode.Restricted,
            )
        val wire = encode { RegisterV3Codec.encode(it, v3, EncodeContext.Empty) }

        val buffer = BufferFactory.Default.wrap(wire, ByteOrder.BIG_ENDIAN)
        val decoded = RegisterV1Codec.decode(buffer, DecodeContext.Empty)

        assertEquals("alpha", decoded.hostname)
        assertEquals(4242, decoded.pid)
        assertEquals(true, decoded.secure)
        // The old reader stops after its own fields; the newer trailing bytes are left unread
        // for the framing layer to discard, not consumed into a field it does not have.
        assertEquals(9, buffer.remaining(), "v1 reader must leave the v3 tail unconsumed")
    }

    // ---- encode side is unconditional --------------------------------------

    @Test
    fun `the encoder always writes optional fields even at their defaults`() {
        // `@SinceVersion` is decode-only tolerance. A value equal to the default is still a value
        // the current producer holds, and it goes on the wire — absence is not a way to shrink
        // the frame, and a peer must never have to infer it.
        val atDefaults = RegisterV3(hostname = "a", pid = 1, secure = false)
        val explicit =
            RegisterV3(
                hostname = "a",
                pid = 1,
                secure = false,
                retries = RegisterV3.DEFAULT_RETRIES,
                build = BuildNumber(0),
                mode = RegisterMode.Standard,
            )
        assertContentEquals(
            encode { RegisterV3Codec.encode(it, atDefaults, EncodeContext.Empty) },
            encode { RegisterV3Codec.encode(it, explicit, EncodeContext.Empty) },
        )
        val v1Size =
            encode {
                RegisterV1Codec.encode(it, RegisterV1("a", 1, false), EncodeContext.Empty)
            }.size
        assertEquals(
            v1Size + 9,
            encode { RegisterV3Codec.encode(it, atDefaults, EncodeContext.Empty) }.size,
            "all three optional fields must be written even when defaulted",
        )
    }

    // ---- bounded nesting ----------------------------------------------------

    @Test
    fun `a length-framed nested optional-trailing message decodes at every revision`() {
        // The permitted nesting shape: @LengthPrefixed bounds the inner extent, so the inner
        // decoder's remaining() means "bytes of the inner message" and the guard is sound.
        for (mode in listOf(RegisterMode.Standard, RegisterMode.Restricted)) {
            val envelope =
                RegisterEnvelope(
                    sequence = 7,
                    register = RegisterV3(hostname = "n", pid = 2, secure = true, mode = mode),
                )
            val wire = encode { RegisterEnvelopeCodec.encode(it, envelope, EncodeContext.Empty) }
            val decoded = decode(wire) { RegisterEnvelopeCodec.decode(it, DecodeContext.Empty) }
            assertEquals(7, decoded.sequence)
            assertEquals(mode, decoded.register.mode)
        }
    }

    @Test
    fun `a nested v1-length body decodes with defaults and does not read past its frame`() {
        // The case the boundedness rule exists for, in its SOUND form: the inner body is a short
        // (v1-length) register, and the envelope has no fields after it — but the length prefix
        // is what makes the inner guard correct, not the absence of siblings. Build the frame by
        // hand so the inner bytes genuinely predate the optional fields.
        val inner = encode { RegisterV1Codec.encode(it, RegisterV1("n", 2, true), EncodeContext.Empty) }
        val framed =
            encode { buffer ->
                buffer.writeInt(7)
                buffer.writeUShort(inner.size.toUShort())
                buffer.write(inner)
            }
        val decoded = decode(framed) { RegisterEnvelopeCodec.decode(it, DecodeContext.Empty) }
        assertEquals(7, decoded.sequence)
        assertEquals("n", decoded.register.hostname)
        assertEquals(RegisterV3.DEFAULT_RETRIES, decoded.register.retries)
        assertEquals(RegisterMode.Standard, decoded.register.mode)
    }

    // ---- helpers -------------------------------------------------------------

    private fun encode(block: (PlatformBuffer) -> Unit): ByteArray {
        val buffer = BufferFactory.Default.allocate(512, byteOrder = ByteOrder.BIG_ENDIAN)
        block(buffer)
        buffer.resetForRead()
        return buffer.readByteArray(buffer.remaining())
    }

    private fun <T> decode(
        wire: ByteArray,
        block: (ReadBuffer) -> T,
    ): T = block(BufferFactory.Default.wrap(wire, ByteOrder.BIG_ENDIAN))
}
