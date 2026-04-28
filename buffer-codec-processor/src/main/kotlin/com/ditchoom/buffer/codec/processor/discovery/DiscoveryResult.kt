package com.ditchoom.buffer.codec.processor.discovery

/**
 * Output of one Discovery (PhaseA) run.
 *
 * `symbols` carries every successfully-discovered declaration — symbols that PhaseA
 * rejected outright (e.g. sealed root with no subclasses) appear only in
 * `diagnostics`. PhaseB consumes both lists.
 *
 * `externalClasses` captures directly-declared supertype info for any class
 * referenced from `@DispatchOn(framing = X::class)` or `@UseCodec(codec = X::class)`.
 * PhaseC's framer / codec conformance checks consume this map keyed by FQN.
 */
data class DiscoveryResult(
    val symbols: List<RawSymbol>,
    val diagnostics: List<DiscoveryDiagnostic>,
    val externalClasses: Map<String, RawClassMetadata> = emptyMap(),
)
