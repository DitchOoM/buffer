package com.ditchoom.buffer.codec.processor.planbuilder

/**
 * One PhaseB / PhaseC diagnostic, ready to surface through KSP's logger.
 *
 * [sourceFqn] points at the offending declaration (class FQN, optionally suffixed with
 * `.field` or `.parameter` for inner-symbol context); empty when the diagnostic is
 * whole-program. [message] is the user-facing explanation — should always name the
 * offending element and, where applicable, suggest a remediation.
 */
data class KspError(
    val message: String,
    val sourceFqn: String,
)
