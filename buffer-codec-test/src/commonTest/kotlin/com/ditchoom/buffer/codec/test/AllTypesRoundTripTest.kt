package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.AllTypesMessage
import com.ditchoom.buffer.codec.test.protocols.AllTypesMessageCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class AllTypesRoundTripTest {
    @Test
    fun `round trip all types`() {
        val original =
            AllTypesMessage(
                byteVal = 42,
                ubyteVal = 200u,
                shortVal = -1000,
                ushortVal = 50000u,
                intVal = -123456,
                uintVal = 3000000000u,
                longVal = -999999999999L,
                ulongVal = 18000000000000000000uL,
                floatVal = 3.14f,
                doubleVal = 2.71828,
                boolVal = true,
                stringVal = "Hello, Codec!",
            )
        val buffer = BufferFactory.Default.allocate(256)
        AllTypesMessageCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = AllTypesMessageCodec.decode(buffer)
        assertAllTypesEqual(original, decoded)
    }

    @Test
    fun `round trip with min max values`() {
        val original =
            AllTypesMessage(
                byteVal = Byte.MIN_VALUE,
                ubyteVal = UByte.MAX_VALUE,
                shortVal = Short.MIN_VALUE,
                ushortVal = UShort.MAX_VALUE,
                intVal = Int.MIN_VALUE,
                uintVal = UInt.MAX_VALUE,
                longVal = Long.MIN_VALUE,
                ulongVal = ULong.MAX_VALUE,
                floatVal = Float.MAX_VALUE,
                doubleVal = Double.MAX_VALUE,
                boolVal = false,
                stringVal = "",
            )
        val buffer = BufferFactory.Default.allocate(256)
        AllTypesMessageCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = AllTypesMessageCodec.decode(buffer)
        assertAllTypesEqual(original, decoded)
    }

    /** Compare field-by-field using float bit equality to avoid JS float precision issues. */
    private fun assertAllTypesEqual(
        expected: AllTypesMessage,
        actual: AllTypesMessage,
    ) {
        assertEquals(expected.byteVal, actual.byteVal, "byteVal")
        assertEquals(expected.ubyteVal, actual.ubyteVal, "ubyteVal")
        assertEquals(expected.shortVal, actual.shortVal, "shortVal")
        assertEquals(expected.ushortVal, actual.ushortVal, "ushortVal")
        assertEquals(expected.intVal, actual.intVal, "intVal")
        assertEquals(expected.uintVal, actual.uintVal, "uintVal")
        assertEquals(expected.longVal, actual.longVal, "longVal")
        assertEquals(expected.ulongVal, actual.ulongVal, "ulongVal")
        assertEquals(expected.floatVal.toBits(), actual.floatVal.toBits(), "floatVal")
        assertEquals(expected.doubleVal, actual.doubleVal, "doubleVal")
        assertEquals(expected.boolVal, actual.boolVal, "boolVal")
        assertEquals(expected.stringVal, actual.stringVal, "stringVal")
    }
}
