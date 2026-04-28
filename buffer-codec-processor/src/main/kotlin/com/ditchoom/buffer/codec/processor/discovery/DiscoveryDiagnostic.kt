package com.ditchoom.buffer.codec.processor.discovery

/**
 * One discovery-time diagnostic. PhaseA emits these instead of throwing or bailing
 * the whole pipeline so downstream phases see a complete picture and can either
 * continue (warnings) or short-circuit (errors).
 *
 * [sourceFqn] points at the canonical name of the offending declaration when it
 * exists — empty when the diagnostic is whole-program (e.g. file-level resolver
 * failure).
 */
data class DiscoveryDiagnostic(
    val severity: Severity,
    val message: String,
    val sourceFqn: String,
) {
    enum class Severity { Error, Warning }
}
