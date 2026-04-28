package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawDirection
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.Direction

/**
 * Maps the discovery-layer [RawDirection] to the IR's [Direction], surfacing the
 * `@Decode` + `@Encode` conflict diagnostic via [resolve].
 *
 * A `RawDirection.Conflict` (PhaseA captured both class-level markers) becomes a
 * dedicated PhaseB error here; absence of class-level markers (`Default`) maps to
 * [Direction.Bidirectional] — PhaseC may further refine when a field-level
 * `@UseCodec` codec is unidirectional.
 */
internal object DirectionResolver {
    fun resolve(symbol: RawSymbol): Either<Nel<KspError>, Direction> =
        when (symbol.direction) {
            RawDirection.Default, RawDirection.Codec -> Direction.Bidirectional.right()
            RawDirection.DecodeOnly -> Direction.DecodeOnly.right()
            RawDirection.EncodeOnly -> Direction.EncodeOnly.right()
            RawDirection.Conflict ->
                Nel
                    .of(
                        KspError(
                            message =
                                "@Decode and @Encode are mutually exclusive on '${symbol.fqn}'. " +
                                    "Pick exactly one, or remove both to default to bidirectional.",
                            sourceFqn = symbol.fqn,
                        ),
                    ).left()
        }

    /** Returns the explicit class-level direction or null when the class defaults to "infer". */
    fun classDirection(symbol: RawSymbol): Direction? =
        when (symbol.direction) {
            RawDirection.DecodeOnly -> Direction.DecodeOnly
            RawDirection.EncodeOnly -> Direction.EncodeOnly
            RawDirection.Codec -> Direction.Bidirectional
            RawDirection.Default -> null
            RawDirection.Conflict -> null
        }
}
