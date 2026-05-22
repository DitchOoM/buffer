package com.ditchoom.buffer.codec.test.protocols.slice11a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.When

/**
 * Capability probe for the emitter widening
 * that lifts `@When @UseCodec val: T?` (sealed-parent inner) and
 * `@RemainingBytes val: List<E>` (sealed-parent element). Pure
 * fixture: no v5 substitution lands until /12.
 *
 * The sealed parent [ProbeSealed] mixes one Exact-wireSize variant
 * ([Tag1]: a single `UByte` body) with one BackPatch-wireSize variant
 * ([Tag2]: `@LengthPrefixed val: String`). The BackPatch variant
 * exercises [ProbeRemainingBytesList]'s `elementIsBackPatch=true`
 * path — without the guard added to `buildWireSizeFun` /
 * `classifyVariantWireSize`, encoding the list would CCE on the
 * runtime `as WireSize.Exact` cast for [Tag2]'s wireSize.
 */
@ProtocolMessage
sealed interface ProbeSealed {
    @ProtocolMessage
    @PacketType(0x01)
    data class Tag1(
        val n: UByte,
    ) : ProbeSealed

    @ProtocolMessage
    @PacketType(0x02)
    data class Tag2(
        @LengthPrefixed val msg: String,
    ) : ProbeSealed
}

/**
 * Hand-written `Codec<ProbeSealed>` delegating to the auto-generated
 * [ProbeSealedCodec]. The probe needs an `@UseCodec(...)` target the
 * KSP processor can resolve in the same round as the sources that
 * reference it; the auto-generated dispatcher object isn't visible
 * from the conditional-field analyzer's first-round resolve, so the
 * probe goes through this hand-written delegate. The v5 substitution
 * exercises the auto-generated-codec reference shape directly via
 * `@UseCodec(V5XReasonCodeCodec::class)`.
 */
object ProbeSealedDelegateCodec : Codec<ProbeSealed> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ProbeSealed = ProbeSealedCodec.decode(buffer, context)

    override fun encode(
        buffer: WriteBuffer,
        value: ProbeSealed,
        context: EncodeContext,
    ) {
        ProbeSealedCodec.encode(buffer, value, context)
    }

    override fun wireSize(
        value: ProbeSealed,
        context: EncodeContext,
    ): WireSize = ProbeSealedCodec.wireSize(value, context)
}

/**
 * Exercises `@When @UseCodec val: T?` where `T` is the sealed parent
 * [ProbeSealed]. The validator's `implementsCodecOf` accepts
 * [ProbeSealedDelegateCodec] (a singleton object implementing
 * `Codec<ProbeSealed>`); the new `ConditionalInner.UseCodecScalar`
 * branch in `analyzeConditionalInner` wires the encode/decode emit.
 *
 * Wire layout:
 *   - `present=false`: `00`
 *   - `present=true, seal=Tag1(n=0x77)`: `01 01 77`
 *   - `present=true, seal=Tag2(msg="hi")`: `01 02 00 02 68 69`
 *     (string prefix is UShort BE per default LengthPrefixed width)
 */
@ProtocolMessage
data class ProbeConditional(
    val present: Boolean,
    @When("present") @UseCodec(ProbeSealedDelegateCodec::class) val seal: ProbeSealed? = null,
)

/**
 * Exercises `@RemainingBytes val xs: List<SealedParent>` — the sister
 * widening to [ProbeConditional] that drops the elementIsDataClass-
 * only restriction on `analyzeRemainingBytesProtocolMessageListField`.
 * The list runs to the buffer's `limit()`; each element decodes via
 * the auto-generated [ProbeSealedCodec] (resolved by emitter via
 * `classNameOf` — no `@UseCodec` reference on the field).
 *
 * Wire layout for `xs = [Tag1(0x42), Tag2("ab"), Tag1(0xFF)]`:
 *   `01 42  02 00 02 61 62  01 FF`
 */
@ProtocolMessage
data class ProbeRemainingBytesList(
    @RemainingBytes val xs: List<ProbeSealed>,
)
