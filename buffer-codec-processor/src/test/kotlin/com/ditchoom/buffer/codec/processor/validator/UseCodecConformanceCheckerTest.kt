package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UseCodecConformanceCheckerTest {
    @Test
    fun `well-formed Codec implementor passes`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.externalField(
                        name = "blob",
                        typeFqn = "test.Bitmap",
                        codecFqn = "test.BitmapCodec",
                    ),
                ),
            )
        val externals =
            mapOf(
                "test.BitmapCodec" to
                    PhaseCFixtures.codecMetadata(
                        codecFqn = "test.BitmapCodec",
                        elementFqn = "test.Bitmap",
                    ),
            )
        Validator.validate(PhaseCFixtures.toMap(leaf), externals).asRightOrFail()
    }

    @Test
    fun `unresolved codec class is rejected with a classpath hint`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.externalField(
                        name = "blob",
                        typeFqn = "test.Bitmap",
                        codecFqn = "test.MissingCodec",
                    ),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(leaf), emptyMap()).asLeftOrFail()
        val msg = errors.all.firstOrNull { "could not be resolved" in it.message }?.message
        assertNotNull(msg, "expected resolution diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("MissingCodec" in msg)
    }

    @Test
    fun `Encoder-only implementor passes (Encoder qualifies as codec-shaped)`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.externalField(
                        name = "blob",
                        typeFqn = "test.Bitmap",
                        codecFqn = "test.BitmapEncoder",
                    ),
                ),
            )
        val externals =
            mapOf(
                "test.BitmapEncoder" to
                    PhaseCFixtures.codecMetadata(
                        codecFqn = "test.BitmapEncoder",
                        elementFqn = "test.Bitmap",
                        codecInterfaceFqn = "com.ditchoom.buffer.codec.Encoder",
                    ),
            )
        Validator.validate(PhaseCFixtures.toMap(leaf), externals).asRightOrFail()
    }

    @Test
    fun `Decoder-only implementor passes`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.externalField(
                        name = "blob",
                        typeFqn = "test.Bitmap",
                        codecFqn = "test.BitmapDecoder",
                    ),
                ),
            )
        val externals =
            mapOf(
                "test.BitmapDecoder" to
                    PhaseCFixtures.codecMetadata(
                        codecFqn = "test.BitmapDecoder",
                        elementFqn = "test.Bitmap",
                        codecInterfaceFqn = "com.ditchoom.buffer.codec.Decoder",
                    ),
            )
        Validator.validate(PhaseCFixtures.toMap(leaf), externals).asRightOrFail()
    }

    @Test
    fun `class with unrelated supertype emits the codec-shape diagnostic`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.externalField(
                        name = "blob",
                        typeFqn = "test.Bitmap",
                        codecFqn = "test.NotACodec",
                    ),
                ),
            )
        val externals = mapOf("test.NotACodec" to PhaseCFixtures.unrelatedClassMetadata("test.NotACodec"))
        val errors = Validator.validate(PhaseCFixtures.toMap(leaf), externals).asLeftOrFail()
        // Slice 5b: PhaseC emits the legacy `does not implement Codec<T>, Decoder<T>, or Encoder<T>`
        // wording so the integration tests asserting the legacy diagnostic shape keep matching.
        val msg = errors.all.firstOrNull { "does not implement" in it.message }?.message
        assertNotNull(msg)
        assertTrue("Encoder" in msg && "Decoder" in msg)
    }
}
