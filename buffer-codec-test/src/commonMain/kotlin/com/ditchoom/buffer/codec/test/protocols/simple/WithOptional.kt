package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WhenTrue

/**
 * Stage E doctrine vector for `@WhenTrue` against a sibling `Boolean`.
 *
 * Smallest-possible conditional-field shape: a leading `Boolean`
 * source followed by a single nullable scalar slot whose presence is
 * driven by that source. Validates the slice-2 emitter capability
 * (sibling-Boolean `@WhenTrue`, `Boolean` as a 1-byte scalar,
 * `WireSize` collapsing to `BackPatch` per Locked Decision row 19,
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
    @WhenTrue("hasExtra") val extra: Int? = null,
)
