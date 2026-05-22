package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.When

/**
 * Doctrine vector for `@When` against a sibling `Boolean`.
 *
 * Smallest-possible conditional-field shape: a leading `Boolean`
 * source followed by a single nullable scalar slot whose presence is
 * driven by that source. Validates the emitter capability
 * (sibling-Boolean `@When`, `Boolean` as a 1-byte scalar,
 * `WireSize` collapsing to `BackPatch` per,
 * and `peekFrameSize` peeking a boolean source statically).
 *
 * Wire layout:
 *   - `WithOptional(hasExtra = false)`            → `00`
 *   - `WithOptional(hasExtra = true,  extra = N)` → `01 NN NN NN NN`
 *
 * Boolean wire form: `00` for `false`, `01` for `true` (any non-zero
 * byte decodes as `true`, but the encoder emits the canonical `01`).
 */
@ProtocolMessage
data class WithOptional(
    val hasExtra: Boolean,
    @When("hasExtra") val extra: Int? = null,
)
