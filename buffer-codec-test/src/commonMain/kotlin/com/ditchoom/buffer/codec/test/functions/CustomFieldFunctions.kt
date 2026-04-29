package com.ditchoom.buffer.codec.test.functions

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.writeVariableByteInteger

/**
 * Reads property-bag entries until the buffer slice is exhausted.
 *
 * Per the [com.ditchoom.buffer.codec.Codec] contract, the buffer is pre-bounded by
 * the framework — the caller's `@LengthPrefixed` (or sibling-length) annotation has
 * already sliced the buffer to the bag's payload window. The decoder walks entries
 * until `remaining() == 0`.
 */
fun ReadBuffer.readPropertyBag(): Map<Int, Int> {
    val props = mutableMapOf<Int, Int>()
    while (remaining() > 0) {
        val tag = readByte().toInt()
        val value = readVariableByteInteger()
        props[tag] = value
    }
    return props
}

/**
 * Writes the property-bag body — entries only. The framework writes any outer length
 * prefix via the field's `@LengthPrefixed` annotation.
 */
fun WriteBuffer.writePropertyBag(props: Map<Int, Int>) {
    props.forEach { (k, v) ->
        writeByte(k.toByte())
        writeVariableByteInteger(v)
    }
}

/** Body byte count — does not include any outer length prefix. */
fun propertyBagSize(props: Map<Int, Int>): Int {
    var bodyLen = 0
    props.forEach { (_, v) ->
        bodyLen += 1 // tag byte
        bodyLen += com.ditchoom.buffer.variableByteSizeInt(v)
    }
    return bodyLen
}
