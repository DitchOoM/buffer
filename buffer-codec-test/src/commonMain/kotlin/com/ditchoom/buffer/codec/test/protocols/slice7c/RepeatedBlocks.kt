package com.ditchoom.buffer.codec.test.protocols.slice7c

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Phase J.M.0 doctrine vector — `@RemainingBytes` on
 * `List<@ProtocolMessage T>`. The body's repeated nested-message
 * elements read until the buffer's limit is reached; the caller
 * is responsible for setting `buffer.setLimit(...)` externally
 * to bound the read region (typical: outer protocol carries a
 * remaining-length variable-length integer that the dispatcher
 * uses to set the limit before delegating).
 *
 * Pairs the `slice 7a` (`@LengthFrom("sibling") List<T>`) and
 * `slice 7b` (`@RemainingBytes List<S>`) shapes:
 *
 * | Annotation         | Element        | Bound by        |
 * |--------------------|----------------|-----------------|
 * | `@LengthFrom`      | `@ProtocolMessage` | sibling field |
 * | `@RemainingBytes`  | scalar (UByte/Byte) | caller-set limit |
 * | `@RemainingBytes`  | `@ProtocolMessage` | caller-set limit |  ← J.M.0
 *
 * Wire layout:
 *
 * ```text
 *   +--------+--------+
 *   |     stream id (UShort BE)         |
 *   +--------+--------+--------+--------+
 *   | block id (UShort BE) | kind | … repeats N times … |
 *   +--------+--------+--------+--------+
 * ```
 *
 * Each `RepeatedBlock` is a fixed 3 bytes (UShort BE + UByte), so
 * total wire = 2 + 3 × N bytes. The fixed-size header before the
 * variable-count payload makes the "bounded by external limit" test
 * non-trivial: decode must skip the header and stop the list at the
 * caller-set limit, not at buffer end.
 *
 * Unblocks: MQTT v3.1.1 SUBSCRIBE / UNSUBSCRIBE topic-filter list
 * shapes (see `PHASE_J_M_BRIEF.md` step 1).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class RepeatedBlock(
    val blockId: UShort,
    val blockKind: UByte,
)

@ProtocolMessage(wireOrder = Endianness.Big)
data class RepeatedBlocks(
    val streamId: UShort,
    @RemainingBytes val blocks: List<RepeatedBlock>,
)
