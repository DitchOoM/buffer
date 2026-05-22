package com.ditchoom.buffer.codec.test.protocols.slice14b

import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec

/**
 * Capability probe for `@FramedBy`. Pure
 * fixture; production substitution (the v3/v5 sealed parents) lands in
 *
 * The class-level annotation transfers framing ownership to the
 * framework: encode emits the prefix carrying the body's encoded byte
 * count via the slicing scheme (see `FramedEncoder.encode`), decode
 * reads the prefix, narrows `buffer.limit()` to bound the body, and
 * asserts strict consumption. The decoded length value never appears
 * on the data class — `remainingLength` and friends disappear from the
 * model, closing the impossible-state class 's
 * `@DerivedLength` only handled for the fixed-suffix shape.
 */
@ProtocolMessage
@FramedBy(MqttRemainingLengthCodec::class)
data class Slice14bFramedFrameFixed(
    val payload: UByte,
    val tail: UShort,
)

/**
 * Variable-body counterpart of [Slice14bFramedFrameFixed]. The
 * `@LengthPrefixed String` suffix makes the body BackPatch-shaped:
 * the field's wire size is unknown until the string is encoded. The
 * slicing scheme handles fixed-suffix and BackPatch-suffix bodies
 * identically — the body bytes are never moved; only the prefix is
 * right-flushed into the slack region. This probe confirms that
 * collapse.
 */
@ProtocolMessage
@FramedBy(MqttRemainingLengthCodec::class)
data class Slice14bFramedFrameVariable(
    @LengthPrefixed val message: String,
)
