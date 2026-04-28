package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FramerTypeMatcherTest {
    @Test
    fun `peek-only framer with matching D passes`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Frame",
                listOf(PhaseCFixtures.variant("test.Frame.A", wire = 1)),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.PeekOnly(framerFqn = ClassName("test", "MyFraming")),
                    ),
            )
        val externals =
            mapOf(
                "test.MyFraming" to
                    PhaseCFixtures.dispatchFramingMetadata(
                        framerFqn = "test.MyFraming",
                        discriminatorFqn = "test.Header",
                    ),
            )
        Validator.validate(PhaseCFixtures.toMap(sealed), externals).asRightOrFail()
    }

    @Test
    fun `framer not on classpath emits resolution diagnostic`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Frame",
                listOf(PhaseCFixtures.variant("test.Frame.A", wire = 1)),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.PeekOnly(framerFqn = ClassName("test", "MissingFraming")),
                    ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed), emptyMap()).asLeftOrFail()
        val msg = errors.all.firstOrNull { "could not be resolved" in it.message }?.message
        assertNotNull(msg)
        assertTrue("MissingFraming" in msg)
    }

    @Test
    fun `framer with unrelated supertype is rejected and the diagnostic mentions DispatchFraming`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Frame",
                listOf(PhaseCFixtures.variant("test.Frame.A", wire = 1)),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.PeekOnly(framerFqn = ClassName("test", "WrongFraming")),
                    ),
            )
        val externals = mapOf("test.WrongFraming" to PhaseCFixtures.unrelatedClassMetadata("test.WrongFraming"))
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed), externals).asLeftOrFail()
        val msg = errors.all.firstOrNull { "directly extend" in it.message }?.message
        assertNotNull(msg)
        assertTrue("DispatchFraming" in msg)
        assertTrue("test.WrongFraming" in msg)
    }

    @Test
    fun `framer parameter binding mismatch reports the actual binding versus the discriminator`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Frame",
                listOf(PhaseCFixtures.variant("test.Frame.A", wire = 1)),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.PeekOnly(framerFqn = ClassName("test", "OtherFraming")),
                    ),
            )
        val externals =
            mapOf(
                "test.OtherFraming" to
                    PhaseCFixtures.dispatchFramingMetadata(
                        framerFqn = "test.OtherFraming",
                        discriminatorFqn = "test.OtherHeader",
                    ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed), externals).asLeftOrFail()
        val msg = errors.all.firstOrNull { "parameter binding" in it.message }?.message
        assertNotNull(msg, "expected parameter-binding diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("test.OtherHeader" in msg && "test.Header" in msg)
    }

    @Test
    fun `Unframed framing skips the matcher entirely`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Frame",
                listOf(PhaseCFixtures.variant("test.Frame.A", wire = 1)),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.Unframed,
                    ),
            )
        Validator.validate(PhaseCFixtures.toMap(sealed), emptyMap()).asRightOrFail()
    }

    @Test
    fun `BodyLength framing rejects a peek-only DispatchFraming framer`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Frame",
                listOf(PhaseCFixtures.variant("test.Frame.A", wire = 1)),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.BodyLength(framerFqn = ClassName("test", "PeekOnlyFraming"), discriminatorBytes = 1),
                    ),
            )
        val externals =
            mapOf(
                "test.PeekOnlyFraming" to
                    PhaseCFixtures.dispatchFramingMetadata(
                        framerFqn = "test.PeekOnlyFraming",
                        discriminatorFqn = "test.Header",
                        bodyLength = false,
                    ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed), externals).asLeftOrFail()
        val msg = errors.all.firstOrNull { "directly extend" in it.message }?.message
        assertNotNull(msg, "expected directly-extend diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("BodyLengthFraming" in msg, "expected BodyLengthFraming named in '$msg'")
    }

    @Test
    fun `BodyLength framing accepts a BodyLengthFraming framer with matching D`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Frame",
                listOf(PhaseCFixtures.variant("test.Frame.A", wire = 1)),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.BodyLength(framerFqn = ClassName("test", "BlFraming"), discriminatorBytes = 1),
                    ),
            )
        val externals =
            mapOf(
                "test.BlFraming" to
                    PhaseCFixtures.dispatchFramingMetadata(
                        framerFqn = "test.BlFraming",
                        discriminatorFqn = "test.Header",
                        bodyLength = true,
                    ),
            )
        Validator.validate(PhaseCFixtures.toMap(sealed), externals).asRightOrFail()
    }
}
