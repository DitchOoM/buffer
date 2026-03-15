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
    // Calculate body size first
    var bodyLen = 0
    props.forEach { (k, v) ->
        bodyLen += 1 // tag byte
        bodyLen += com.ditchoom.buffer.variableByteSizeInt(v)
    }
    // Write length prefix as VBI
    writeVariableByteInteger(bodyLen)
    // Write entries
    props.forEach { (k, v) ->
        writeByte(k.toByte())
        writeVariableByteInteger(v)
    }
}
