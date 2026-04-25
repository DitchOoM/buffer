package com.ditchoom.buffer.codec

/**
 * Diagnostic sink populated by sealed dispatchers configured with
 * `@DispatchOn(bodyLength = …)`. Register an instance under [BodyLengthKey] in the
 * decode context to capture the VBI / length-prefix body length the dispatcher
 * consumed on the wire.
 *
 * ```kotlin
 * val sink = BodyLengthSink()
 * val ctx = DecodeContext.Empty.with(BodyLengthKey, sink)
 * MqttPacketCodec.decode(buffer, ctx)
 * println("dispatcher saw body length = ${sink.value}")
 * ```
 *
 * The dispatcher pays no allocation cost on the hot path when [BodyLengthKey] is
 * absent from the context — the sink is purely opt-in.
 */
class BodyLengthSink {
    /** The body length the dispatcher consumed, or `null` if no decode has run with this sink. */
    var value: Int? = null
}

/** Context key for registering a [BodyLengthSink] on a decode context. */
data object BodyLengthKey : CodecContext.Key<BodyLengthSink>()
