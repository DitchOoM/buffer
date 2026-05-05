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
