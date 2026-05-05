package com.ditchoom.buffer.codec.test.protocols.slice14a

import com.ditchoom.buffer.codec.annotations.DerivedLength
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec

/**
 * Phase J.M.5 slice 14a — capability probe for `@DerivedLength`. Pure
 * fixture; no production substitution lands in this slice.
 *
 * The annotation marks the `length` field as framework-derived at
 * encode time. The MVP narrows to all-`FixedSize` suffix (here:
 * `payload: UByte` and `tail: UShort` = 3 fixed bytes). Encode emit
 * computes `__lengthDerived = 3u`, asserts the caller-supplied
 * `length` matches, and writes the prefix via
 * [MqttRemainingLengthCodec]. Decode unchanged from the existing
 * `BoundingLengthCodec` path — the codec's `applyBound` narrows the
 * buffer's limit, subsequent fields decode inside.
 *
 * Wire layout: `<vbi length=3> <payload> <tail high> <tail low>` =
 * `03 PP HH LL` (4 bytes total).
 *
 * Mismatch failure mode: constructing with `length = 99u` and calling
 * encode throws `EncodeException` naming the framework-derived value
 * (3) so the user can see the discrepancy.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class Slice14aDerivedFrame(
    @DerivedLength @UseCodec(MqttRemainingLengthCodec::class) val length: UInt = 3u,
    val payload: UByte,
    val tail: UShort,
)
