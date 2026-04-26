package com.ditchoom.buffer.codec.test.functions

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.writeVariableByteInteger

fun ReadBuffer.readPropertyBag(): Map<Int, Int> {
    val len = readVariableByteInteger()
    val end = position() + len
    val props = mutableMapOf<Int, Int>()
    while (position() < end) {
        val tag = readByte().toInt()
        val value = readVariableByteInteger()
        props[tag] = value
    }
    return props
}

fun WriteBuffer.writePropertyBag(props: Map<Int, Int>) {
    val bodyLen = propertyBagBodySize(props)
    // Write length prefix as VBI
    writeVariableByteInteger(bodyLen)
    // Write entries
    props.forEach { (k, v) ->
        writeByte(k.toByte())
        writeVariableByteInteger(v)
    }
}

private fun propertyBagBodySize(props: Map<Int, Int>): Int {
    var bodyLen = 0
    props.forEach { (_, v) ->
        bodyLen += 1 // tag byte
        bodyLen += com.ditchoom.buffer.variableByteSizeInt(v)
    }
    return bodyLen
}

fun propertyBagSize(props: Map<Int, Int>): Int {
    val bodyLen = propertyBagBodySize(props)
    return com.ditchoom.buffer.variableByteSizeInt(bodyLen) + bodyLen
}
