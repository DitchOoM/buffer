package com.ditchoom.buffer.codec.processor.ir

import com.squareup.kotlinpoet.ClassName

/** How a sealed root dispatches to its variants. */
sealed interface DispatchShape {
    /** Dispatch on the first wire byte directly — no typed discriminator class. */
    data object RawByte : DispatchShape

    /** Dispatch via a typed discriminator with explicit framing mode. */
    data class TypedDiscriminator(
        val disc: DiscriminatorShape,
        val framing: FramingMode,
    ) : DispatchShape
}

/** Shape of the discriminator type used by `DispatchShape.TypedDiscriminator`. */
sealed interface DiscriminatorShape {
    /** Discriminator is a `value class` wrapping a primitive (e.g., `MqttFixedHeader(raw: UByte)`). */
    data class ValueClass(
        val inner: PrimitiveKind,
        val innerProp: String,
        val codec: ClassName,
        val dispatchProp: String,
        val wireRange: ClosedRange<Int>,
    ) : DiscriminatorShape

    /** Discriminator is a `data class` with multiple fields used during dispatch. */
    data class DataClass(
        val params: List<DiscParam>,
        val codec: ClassName,
        val dispatchProp: String,
    ) : DiscriminatorShape
}

/** A constructor parameter contributing to a `DiscriminatorShape.DataClass`. */
data class DiscParam(
    val name: String,
    val kind: PrimitiveKind,
    val wireBytes: Int,
)

/**
 * How the dispatcher slices the body bytes for a sealed root.
 *
 * - `Unframed`: the dispatcher hands the original buffer to the variant (RIFF, WebSocket pre-retrofit).
 * - `PeekOnly`: the dispatcher consults a framer only for non-consuming peek (HTTP/2, TLS, retrofitted WebSocket).
 * - `BodyLength`: the dispatcher reads + writes a body-length prefix and slices the body for variants (MQTT, AMQP).
 */
sealed interface FramingMode {
    data object Unframed : FramingMode

    data class PeekOnly(
        val framerFqn: ClassName,
    ) : FramingMode

    data class BodyLength(
        val framerFqn: ClassName,
        val discriminatorBytes: Int,
    ) : FramingMode {
        init {
            require(discriminatorBytes >= 1) {
                "FramingMode.BodyLength.discriminatorBytes must be >= 1, got $discriminatorBytes"
            }
        }
    }
}

/** A wire-byte match that selects one variant from a sealed root. */
sealed interface WireMatch {
    val subclass: TypeFqn

    /** Single byte (or single discriminator value): `@PacketType(wire)`. */
    data class Point(
        override val subclass: TypeFqn,
        val wire: Int,
    ) : WireMatch

    /** Inclusive byte range: `@PacketTypeRange(from, to)`. */
    data class Range(
        override val subclass: TypeFqn,
        val from: Int,
        val to: Int,
    ) : WireMatch {
        init {
            require(from <= to) { "WireMatch.Range from=$from must be <= to=$to" }
        }
    }
}
