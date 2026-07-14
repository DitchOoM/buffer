package com.ditchoom.buffer.flow

/**
 * Marks the datagram trichotomy ([SocketAddress] / [Datagram] / [DatagramSource] / [DatagramSink] /
 * [DatagramChannel] / [DatagramMux] and their value types) as **experimental and opt-in**.
 *
 * The datagram surface is the unreliable, pre-framed, addressed analogue of the byte trichotomy
 * ([ByteSource] / [ByteSink] / [ByteStream]). Unlike the byte surface, it is *incubating*: it lands
 * ahead of its platform socket actuals (`:socket-udp`) and its conformance witnesses (quiche, ICE,
 * SFU/TURN, mDNS, DNS/STUN) so those can adversarially exercise it. Changing an opt-in-experimental
 * API is not a semver-major break — that is exactly what this marker buys. The marker is dropped
 * only once conformance is green across every witness and platform, at which point the surface
 * becomes stable public API.
 *
 * Opt in with `@OptIn(ExperimentalDatagramApi::class)` at the call site, or propagate by annotating
 * your own declaration `@ExperimentalDatagramApi`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "The datagram trichotomy is experimental and may change until conformance is proven " +
            "across all witnesses and platforms. Opt in with @OptIn(ExperimentalDatagramApi::class).",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalDatagramApi
