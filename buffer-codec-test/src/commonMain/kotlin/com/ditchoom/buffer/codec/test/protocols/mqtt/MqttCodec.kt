package com.ditchoom.buffer.codec.test.protocols.mqtt

/**
 * Convenience alias for the MQTT control-packet dispatcher codec.
 *
 * Consumers say `MqttCodec<JpegImage>(JpegImageCodec)` rather than the longer
 * `MqttPacketCodec<JpegImage>(JpegImageCodec)`. Hand-written (consumer-side)
 * rather than emitted: a one-line typealias doesn't justify processor
 * complexity and would otherwise force a naming-collision burden on every
 * consumer that uses the generated codec name directly.
 *
 * The alias preserves the type variable (`<P : Payload>`) so
 * generic instantiation reads cleanly at the call site.
 */
typealias MqttCodec<P> = MqttPacketCodec<P>
