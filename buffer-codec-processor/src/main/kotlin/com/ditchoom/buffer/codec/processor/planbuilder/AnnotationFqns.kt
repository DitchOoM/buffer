@file:Suppress("ktlint:standard:property-naming")

package com.ditchoom.buffer.codec.processor.planbuilder

/**
 * String FQN constants for every annotation the planbuilder consumes.
 *
 * Names mirror the annotation's simple name verbatim so call sites read
 * `AnnotationFqns.LengthPrefixed` rather than `AnnotationFqns.LENGTH_PREFIXED` —
 * the file-level suppress keeps that intentional one-to-one mapping.
 */
internal object AnnotationFqns {
    private const val PKG = "com.ditchoom.buffer.codec.annotations"

    const val ProtocolMessage = "$PKG.ProtocolMessage"
    const val Decode = "$PKG.Decode"
    const val Encode = "$PKG.Encode"
    const val DispatchOn = "$PKG.DispatchOn"
    const val DispatchValue = "$PKG.DispatchValue"
    const val PacketType = "$PKG.PacketType"
    const val PacketTypeRange = "$PKG.PacketTypeRange"
    const val DiscriminatorField = "$PKG.DiscriminatorField"
    const val LengthPrefixed = "$PKG.LengthPrefixed"
    const val LengthFrom = "$PKG.LengthFrom"
    const val RemainingBytes = "$PKG.RemainingBytes"
    const val VariableByteInteger = "$PKG.VariableByteInteger"
    const val WireBytes = "$PKG.WireBytes"
    const val WireOrder = "$PKG.WireOrder"
    const val When_ = "$PKG.When"
    const val WhenTrue = "$PKG.WhenTrue"
    const val WhenRemaining = "$PKG.WhenRemaining"
    const val UseCodec = "$PKG.UseCodec"
    const val Payload = "$PKG.Payload"
}
