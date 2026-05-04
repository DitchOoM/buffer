package com.ditchoom.buffer.codec.test.protocols.mqtt

/**
 * Stage H slice 10e — convenience alias for the MQTT control-packet
 * dispatcher codec.
 *
 * Per PHASE_9_RESET §"Stage H — Payload SAM" the spec calls for a
 * `MqttCodec` alias on the dispatcher; consumers say
 * `MqttCodec<JpegImage>(JpegImageCodec)` rather than the longer
 * `MqttPacketCodec<JpegImage>(JpegImageCodec)`. Hand-written
 * (consumer-side) rather than emitted: a one-line typealias doesn't
 * justify processor complexity and would otherwise force a
 * naming-collision burden on every consumer that uses the
 * generated codec name directly.
 *
 * The alias preserves the type variable (`<P : Payload>`) so
 * generic instantiation reads cleanly at the call site.
 */
typealias MqttCodec<P> = MqttPacketCodec<P>
