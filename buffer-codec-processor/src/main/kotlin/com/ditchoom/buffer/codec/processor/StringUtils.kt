package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * Flattens the enclosing-type chain into a codec object name so that
 * two same-named nested classes in the same package don't collide at
 * KSP file write time (issue #156).
 *
 * Top-level: `MqttPacketConnect` → `MqttPacketConnectCodec`
 * Nested:    `MqttPacket.SubAck` → `MqttPacketSubAckCodec`
 */
internal fun KSClassDeclaration.flattenedCodecName(): String = enclosingSimpleNames().joinToString("") + "Codec"

internal fun KSClassDeclaration.enclosingSimpleNames(): List<String> {
    val names = mutableListOf<String>()
    var current: KSClassDeclaration? = this
    while (current != null) {
        names.add(0, current.simpleName.asString())
        current = current.parentDeclaration as? KSClassDeclaration
    }
    return names
}
