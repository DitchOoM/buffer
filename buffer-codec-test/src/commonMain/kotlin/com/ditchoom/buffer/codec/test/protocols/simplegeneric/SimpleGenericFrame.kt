package com.ditchoom.buffer.codec.test.protocols.simplegeneric

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Generic payload under a **simple `@PacketType`** dispatcher (no
 * `@DispatchOn`, no `@FramedBy`).
 *
 * This is the shape from issue #176, declared correctly: the sealed parent
 * carries `<out P : Payload>` so the dispatcher can bind `P` and thread a
 * constructor-injected `payloadCodec: Codec<P>` to its generic variants. The
 * issue's original (raw, non-generic parent) form is rejected by
 * `validateGenericPayloadVariantShape`; this form is the one that works.
 *
 * Until now this exact combination — a generic parent under simple
 * `@PacketType` (`Generic × FixedByte`) — silently emitted broken monomorphic
 * code (SUPPORT_MATRIX backlog #4/#6: the dispatcher was always an `object`,
 * erasing `P`). It now generates
 * `class SimpleGenericFrameCodec<P>(payloadCodec: Codec<P>)`, threading the
 * codec to the generic [Command] variant while the non-generic [Status]
 * variant (extending `SimpleGenericFrame<Nothing>`) keeps a static codec ref.
 *
 * Wire layout this probe pins down (single-byte fields, so no byte-order
 * concern):
 *
 * ```text
 * Command<TextPayload>(counter = 0x42, payload = "hi"):
 *   0A          discriminator (consumed by the dispatcher)
 *   42          counter
 *   68 69       @RemainingBytes payload "hi"
 *
 * Status(code = 0x7F):
 *   A0          discriminator
 *   7F          code
 * ```
 */
@ProtocolMessage
sealed interface SimpleGenericFrame<out P : Payload> {
    @ProtocolMessage
    @PacketType(0x0A)
    data class Command<P : Payload>(
        val counter: UByte,
        @RemainingBytes val payload: P,
    ) : SimpleGenericFrame<P>

    @ProtocolMessage
    @PacketType(0xA0)
    data class Status(
        val code: UByte,
    ) : SimpleGenericFrame<Nothing>
}
