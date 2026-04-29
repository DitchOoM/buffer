package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionalValidatorTest {
    private class TestLogger : KSPLogger {
        val errors = mutableListOf<String>()

        override fun logging(
            message: String,
            symbol: KSNode?,
        ) {}

        override fun info(
            message: String,
            symbol: KSNode?,
        ) {}

        override fun warn(
            message: String,
            symbol: KSNode?,
        ) {}

        override fun error(
            message: String,
            symbol: KSNode?,
        ) {
            errors.add(message)
        }

        override fun exception(e: Throwable) {}
    }

    private fun field(
        name: String,
        typeName: String,
        strategy: FieldReadStrategy,
        nullable: Boolean = false,
        condition: FieldCondition? = null,
        hasDefault: Boolean = condition != null,
    ): FieldInfo =
        FieldInfo(
            name = name,
            typeName = typeName,
            strategy = strategy,
            isNullable = nullable,
            condition = condition,
            parameter = null,
            hasDefault = hasDefault,
        )

    @Test
    fun `valid boolean condition passes`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("enabled", "kotlin.Boolean", FieldReadStrategy.BooleanField),
                field(
                    "data",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = true,
                    condition = FieldCondition.WhenTrue("enabled"),
                ),
            )
        assertTrue(validator.validate(fields))
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `non-existent referenced field fails`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field(
                    "data",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = true,
                    condition = FieldCondition.WhenTrue("missing"),
                ),
            )
        assertFalse(validator.validate(fields))
    }

    @Test
    fun `non-boolean referenced field fails`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("count", "kotlin.Int", FieldReadStrategy.IntField),
                field(
                    "data",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = true,
                    condition = FieldCondition.WhenTrue("count"),
                ),
            )
        assertFalse(validator.validate(fields))
    }

    @Test
    fun `non-nullable conditional field fails`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("enabled", "kotlin.Boolean", FieldReadStrategy.BooleanField),
                field(
                    "data",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = false,
                    condition = FieldCondition.WhenTrue("enabled"),
                ),
            )
        assertFalse(validator.validate(fields))
    }

    @Test
    fun `conditional referencing later field fails`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field(
                    "data",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = true,
                    condition = FieldCondition.WhenTrue("enabled"),
                ),
                field("enabled", "kotlin.Boolean", FieldReadStrategy.BooleanField),
            )
        assertFalse(validator.validate(fields))
    }

    @Test
    fun `conditional field without default value fails`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("enabled", "kotlin.Boolean", FieldReadStrategy.BooleanField),
                field(
                    "data",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = true,
                    condition = FieldCondition.WhenTrue("enabled"),
                    hasDefault = false,
                ),
            )
        assertFalse(validator.validate(fields))
        assertTrue(logger.errors.any { it.contains("default value") })
    }

    @Test
    fun `dotted expression on value class passes`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field(
                    "flags",
                    "test.Flags",
                    FieldReadStrategy.ValueClassField(FieldReadStrategy.UByteField, "test.Flags"),
                ),
                field(
                    "data",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = true,
                    condition = FieldCondition.WhenTrue("flags.willFlag"),
                ),
            )
        assertTrue(validator.validate(fields))
    }
}
