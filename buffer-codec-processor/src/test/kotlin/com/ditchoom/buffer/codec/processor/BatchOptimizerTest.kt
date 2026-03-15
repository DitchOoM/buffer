package com.ditchoom.buffer.codec.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatchOptimizerTest {
    private val optimizer = BatchOptimizer()

    private fun field(
        name: String,
        strategy: FieldReadStrategy,
        condition: FieldCondition? = null,
    ): FieldInfo =
        FieldInfo(
            name = name,
            typeName = "kotlin.Int",
            strategy = strategy,
            isNullable = condition != null,
            condition = condition,
            parameter = null,
        )

    @Test
    fun `three UByte fields batch first two into readShort`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.UByteField),
                field("b", FieldReadStrategy.UByteField),
                field("c", FieldReadStrategy.UByteField),
            )
        val result = optimizer.optimize(fields)
        // First two batch into readShort (2 bytes), third is standalone
        assertEquals(2, result.size)
        assertTrue(result[0] is CodegenItem.Batched)
        val batch = (result[0] as CodegenItem.Batched).group
        assertEquals(2, batch.totalBytes)
        assertEquals(2, batch.fields.size)
        assertEquals("readShort", batch.readMethod)
        assertTrue(result[1] is CodegenItem.Single)
    }

    @Test
    fun `eight UByte fields batch into single readLong`() {
        val fields = (1..8).map { field("f$it", FieldReadStrategy.UByteField) }
        val result = optimizer.optimize(fields)
        assertEquals(1, result.size)
        assertTrue(result[0] is CodegenItem.Batched)
        assertEquals("readLong", (result[0] as CodegenItem.Batched).group.readMethod)
    }

    @Test
    fun `nine UByte fields split into readLong plus standalone`() {
        val fields = (1..9).map { field("f$it", FieldReadStrategy.UByteField) }
        val result = optimizer.optimize(fields)
        assertEquals(2, result.size)
        assertTrue(result[0] is CodegenItem.Batched)
        assertEquals("readLong", (result[0] as CodegenItem.Batched).group.readMethod)
        assertEquals(8, (result[0] as CodegenItem.Batched).group.totalBytes)
    }

    @Test
    fun `variable length field breaks batch`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.UByteField),
                field("b", FieldReadStrategy.LengthPrefixedStringField("Short")),
                field("c", FieldReadStrategy.UByteField),
            )
        val result = optimizer.optimize(fields)
        // a is standalone (single field, not batched), b is standalone, c is standalone
        assertEquals(3, result.size)
    }

    @Test
    fun `conditional field breaks batch`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.UByteField),
                field("b", FieldReadStrategy.UByteField, FieldCondition.WhenTrue("a")),
                field("c", FieldReadStrategy.UByteField),
            )
        val result = optimizer.optimize(fields)
        // a standalone, b standalone (conditional), c standalone
        assertEquals(3, result.size)
    }

    @Test
    fun `six UShort fields batch into readLong plus readInt`() {
        val fields = (1..6).map { field("f$it", FieldReadStrategy.UShortField) }
        val result = optimizer.optimize(fields)
        assertEquals(2, result.size)
        assertTrue(result[0] is CodegenItem.Batched)
        assertEquals("readLong", (result[0] as CodegenItem.Batched).group.readMethod)
        assertEquals(8, (result[0] as CodegenItem.Batched).group.totalBytes)
        assertTrue(result[1] is CodegenItem.Batched)
        assertEquals("readInt", (result[1] as CodegenItem.Batched).group.readMethod)
        assertEquals(4, (result[1] as CodegenItem.Batched).group.totalBytes)
    }

    @Test
    fun `single field is not batched`() {
        val fields = listOf(field("a", FieldReadStrategy.IntField))
        val result = optimizer.optimize(fields)
        assertEquals(1, result.size)
        assertTrue(result[0] is CodegenItem.Single)
    }

    @Test
    fun `three byte plus one byte batches into readInt`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.PrimitiveField(Primitive.INT, wireBytes = 3)),
                field("b", FieldReadStrategy.UByteField),
            )
        val result = optimizer.optimize(fields)
        assertEquals(1, result.size)
        assertTrue(result[0] is CodegenItem.Batched)
        assertEquals("readInt", (result[0] as CodegenItem.Batched).group.readMethod)
        assertEquals(4, (result[0] as CodegenItem.Batched).group.totalBytes)
    }

    @Test
    fun `two three byte fields not aligned emitted individually`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.PrimitiveField(Primitive.INT, wireBytes = 3)),
                field("b", FieldReadStrategy.PrimitiveField(Primitive.INT, wireBytes = 3)),
            )
        val result = optimizer.optimize(fields)
        // 3 + 3 = 6 bytes, not aligned to 2/4/8
        assertEquals(2, result.size)
        assertTrue(result[0] is CodegenItem.Single)
        assertTrue(result[1] is CodegenItem.Single)
    }

    @Test
    fun `three byte plus five byte batches into readLong`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.PrimitiveField(Primitive.INT, wireBytes = 3)),
                field("b", FieldReadStrategy.PrimitiveField(Primitive.LONG, wireBytes = 5)),
            )
        val result = optimizer.optimize(fields)
        assertEquals(1, result.size)
        assertTrue(result[0] is CodegenItem.Batched)
        assertEquals("readLong", (result[0] as CodegenItem.Batched).group.readMethod)
        assertEquals(8, (result[0] as CodegenItem.Batched).group.totalBytes)
    }

    @Test
    fun `BatchGroup rejects mismatched totalBytes`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.UByteField),
                field("b", FieldReadStrategy.UByteField),
            )
        assertFailsWith<IllegalArgumentException> {
            BatchGroup(fields, totalBytes = 99, readMethod = "readShort")
        }
    }

    @Test
    fun `UByte plus UShort not aligned emitted individually`() {
        val fields =
            listOf(
                field("a", FieldReadStrategy.UByteField),
                field("b", FieldReadStrategy.UShortField),
            )
        val result = optimizer.optimize(fields)
        // 1 + 2 = 3 bytes, not aligned to 2/4/8 so no batching
        assertEquals(2, result.size)
        assertTrue(result[0] is CodegenItem.Single)
        assertTrue(result[1] is CodegenItem.Single)
    }
}
