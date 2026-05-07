package com.ditchoom.buffer.codec.test.protocols.slice15a

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec

/**
 * Phase J.M.5 slice 15a — capability probe for `@LengthPrefixed
 * @UseCodec(C::class) val: T : Payload`. Scalar counterpart of slice
 * 11's `@LengthPrefixed @UseCodec val: List<E>`.
 *
 * Wire shape: a fixed-width unsigned-int prefix (default 2 bytes /
 * UShort BE) followed by exactly that many body bytes consumed by `C`.
 * The framework owns the prefix; `C` is a `Codec<T>` (NOT a
 * `BoundingLengthCodec`) — the prefix tells the framework how many
 * bytes to hand to `C`.
 *
 * `T : Payload` is required by the validator (slice 15 D2 — clusters
 * the "typed binary data crossing the codec boundary" concept under
 * one marker).
 *
 * The [BinaryData] / [BinaryDataCodec] fixture initially landed here in
 * slice 15a; slice 15c promoted them to
 * `com.ditchoom.buffer.codec.test.protocols.payload` so the v5 property
 * variants (CorrelationData / AuthenticationData) and the v3/v5
 * Connect.{willPayload, password} fields can reuse them without
 * coupling production fixtures onto the slice 15a probe package.
 */
@ProtocolMessage
data class Slice15aLengthPrefixedPayload(
    @LengthPrefixed @UseCodec(BinaryDataCodec::class) val data: BinaryData,
)
