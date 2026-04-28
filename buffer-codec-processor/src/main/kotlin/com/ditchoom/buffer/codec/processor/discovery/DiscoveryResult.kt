package com.ditchoom.buffer.codec.processor.discovery

/**
 * Output of one Discovery (PhaseA) run.
 *
 * `symbols` carries every successfully-discovered declaration — symbols that PhaseA
 * rejected outright (e.g. sealed root with no subclasses) appear only in
 * `diagnostics`. PhaseB consumes both lists.
 */
data class DiscoveryResult(
    val symbols: List<RawSymbol>,
    val diagnostics: List<DiscoveryDiagnostic>,
)
