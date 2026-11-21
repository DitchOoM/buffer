package com.ditchoom.buffer

/**
 * Marks the fan-out / shared-send surface ([SharedBytes][com.ditchoom.buffer.pool.SharedBytes],
 * `SharedFrame` / `ContextFreeCodec` in `buffer-codec`, and the `SendMode.Handoff` arm plus its
 * policy types in `buffer-flow`) as **experimental and opt-in**.
 *
 * The default send path (`SendMode.AwaitWritten`) is stable from day one — it preserves the
 * observable semantics existing consumers already rely on. The gated surface is the part with
 * genuine design freedom left: bounded-queue loss policy, linger semantics, and cross-connection
 * encode sharing. Changing an opt-in-experimental API is not a semver-major break — that is what
 * this marker buys while downstream adoption exercises the shape. Stabilization (dropping the
 * marker) requires the sealed hierarchies gated here to be finalized first, since adding an arm
 * to a stabilized sealed type breaks exhaustive `when`s in consumers.
 *
 * Opt in with `@OptIn(ExperimentalFanoutApi::class)` at the call site, or propagate by annotating
 * your own declaration `@ExperimentalFanoutApi`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "The fan-out/shared-send surface is experimental and may change until its sealed " +
            "hierarchies are finalized. Opt in with @OptIn(ExperimentalFanoutApi::class).",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalFanoutApi
