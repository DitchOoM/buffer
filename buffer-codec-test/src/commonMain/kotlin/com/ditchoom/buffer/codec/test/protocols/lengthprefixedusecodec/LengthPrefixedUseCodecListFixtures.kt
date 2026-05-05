package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec

/**
 * Phase I.1 step 11 doctrine vector — exercises `@LengthPrefixed
 * @UseCodec(C::class) val xs: List<E>` where `C` is a
 * `BoundingLengthCodec<UInt>` (here [MqttRemainingLengthCodec], the v3
 * MQTT remaining-length variable-byte codec) and `E` is a
 * `@ProtocolMessage data class`. Drives the v5 MQTT property-list shape
 * standalone — no MQTT-packet integration yet (J.M.5 owns that).
 *
 * The codec reads/writes the body byte count via its var-byte-int wire
 * shape and applies the resulting bound to `buffer.limit()`; elements
 * are read element-by-element via [PropertyEntryCodec] inside the
 * bounded region. Encode pre-measures body bytes via the element
 * codec's `wireSize` (cast to `Exact`), writes the prefix via the user
 * codec, then iterates and encodes elements.
 *
 * Each property entry is `[id (1 byte) | value (4 BE bytes)]` = 5 bytes
 * — an element wire size that exercises `wireSize` Exact composition
 * via the `as Exact` runtime cast.
 */
@ProtocolMessage
data class PropertyEntry(
    val id: UByte,
    val value: UInt,
)

/**
 * Wire layout `[var-byte-int length | properties]`. Single-field frame —
 * the property bag is the entire message. Peek is `Complete` when the
 * codec can decode the prefix value and the bytes-available is at least
 * `prefixWidth + value.toInt()`; otherwise `NeedsMoreData`.
 */
@ProtocolMessage
data class PropertyBagFrame(
    @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<PropertyEntry>,
)

/**
 * Wire layout `[tag (1 byte) | var-byte-int length | properties]`.
 * Exercises the `priorBytes` computation in the peek walker — the
 * walker must skip 1 byte before the prefix codec can decode the body
 * byte count.
 */
@ProtocolMessage
data class TaggedPropertyBag(
    val tag: UByte,
    @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<PropertyEntry>,
)

/**
 * Phase J.M.5 audit-2b regression vector — a data class element whose
 * `wireSize` returns `BackPatch` (due to the `@LengthPrefixed val:
 * String` field, per row-15 doctrine). Before audit-2b the element was
 * routed through the pre-measure encode path (because the analyzer's
 * `elementIsSealed` flag was driven solely by `Modifier.SEALED`), and
 * the `(ElementCodec.wireSize(it, context) as WireSize.Exact).bytes`
 * cast would `ClassCastException` at runtime. After audit-2b the
 * analyze-time predicate `detectElementBackPatch` walks the element's
 * primary-constructor params and routes any element with `@When` /
 * `@RemainingBytes` / `@UseCodec` / `@LengthPrefixed val: String`
 * through the scratch encode path.
 *
 * Wire layout per element: `[lpStringLen (2 bytes BE) | lpStringBytes |
 * value (1 byte)]`. The full bag's wire layout is `[var-byte-int length
 * | elements]`.
 */
@ProtocolMessage
data class StringTaggedProperty(
    @LengthPrefixed val tag: String,
    val value: UByte,
)

/**
 * Phase J.M.5 audit-2b regression vector — see [StringTaggedProperty].
 */
@ProtocolMessage
data class StringTaggedPropertyBag(
    @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<StringTaggedProperty>,
)
