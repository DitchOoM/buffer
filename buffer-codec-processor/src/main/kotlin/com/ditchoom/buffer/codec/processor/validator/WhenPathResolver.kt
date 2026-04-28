package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.BooleanExpression
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError

/**
 * Resolves every `@When` expression's [BooleanExpression.FieldRef] paths against the
 * field map of the enclosing class.
 *
 * Resolution rules:
 *  - The first segment must be the name of a field declared on the enclosing class
 *    (or a sealed-root variant) at any position. (Fields can reference siblings declared
 *    later because conditional evaluation happens at runtime, not at decode-position.)
 *  - For multi-segment paths (`flags.willFlag`), the first segment names the field on
 *    the enclosing class; each subsequent segment names a field on the *type* the
 *    previous segment resolved to. Resolution recurses through plans only — types not
 *    declared as `@ProtocolMessage` halt the walk and we accept the path conservatively.
 *
 * Failure diagnostics include the available field paths the user could have meant
 * (the field names at the level the resolution failed) so a typo surfaces with an
 * actionable hint.
 */
internal object WhenPathResolver {
    fun check(plans: Map<TypeFqn, Plan>): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            when (plan) {
                is Plan.Object_ -> Unit
                is Plan.Leaf -> walkFields(plans, plan.decl, plan.fields, errors)
                is Plan.Sealed_ ->
                    for (v in plan.variants) {
                        walkFields(plans, v.decl, v.fields, errors)
                    }
            }
        }
        return errors
    }

    private fun walkFields(
        plans: Map<TypeFqn, Plan>,
        owner: TypeFqn,
        fields: List<FieldPlan>,
        errors: MutableList<KspError>,
    ) {
        for (f in fields) {
            val cond = f.conditionality as? Conditionality.WhenExpr ?: continue
            collectFieldRefs(cond.expr).forEach { ref ->
                resolvePath(plans, owner, fields, ref.path, f.name)?.let { errors += it }
            }
        }
    }

    private fun collectFieldRefs(expr: BooleanExpression): List<BooleanExpression.FieldRef> =
        when (expr) {
            is BooleanExpression.FieldRef -> listOf(expr)
            is BooleanExpression.RemainingGte -> emptyList()
            is BooleanExpression.Eq -> collectFieldRefs(expr.lhs)
            is BooleanExpression.Gt -> collectFieldRefs(expr.lhs)
        }

    private fun resolvePath(
        plans: Map<TypeFqn, Plan>,
        owner: TypeFqn,
        ownerFields: List<FieldPlan>,
        path: List<String>,
        fieldName: String,
    ): KspError? {
        if (path.isEmpty()) return null
        val firstName = path.first()
        val firstField =
            ownerFields.firstOrNull { it.name == firstName }
                ?: return KspError(
                    message =
                        "@When on '${owner.canonical}.$fieldName' references unknown field '${path.joinToString(".")}': " +
                            "no field named '$firstName' on '${owner.canonical}'. " +
                            "Available: ${availableFieldsHint(ownerFields)}.",
                    sourceFqn = "${owner.canonical}.$fieldName",
                )
        if (path.size == 1) return null
        var cursorType = firstField.type
        var visitedField = firstField
        for (i in 1 until path.size) {
            val nextName = path[i]
            val cursorPlan = plans[cursorType]
            if (cursorPlan == null) {
                // Type isn't a @ProtocolMessage we know — accept conservatively, the user
                // is referencing a property on an external type. PhaseD relies on Kotlin's
                // own type checker to catch any mistakes there.
                return null
            }
            val nestedFields =
                when (cursorPlan) {
                    is Plan.Leaf -> cursorPlan.fields
                    is Plan.Object_ -> emptyList()
                    is Plan.Sealed_ -> emptyList()
                }
            val nested =
                nestedFields.firstOrNull { it.name == nextName }
                    ?: return KspError(
                        message =
                            "@When on '${owner.canonical}.$fieldName' references unknown field " +
                                "'${path.joinToString(".")}': '${cursorType.canonical}' has no field " +
                                "named '$nextName'. Available on '${cursorType.canonical}': " +
                                "${availableFieldsHint(nestedFields)}.",
                        sourceFqn = "${owner.canonical}.$fieldName",
                    )
            cursorType = nested.type
            visitedField = nested
        }
        if (visitedField.strategy is FieldStrategy.Spi) Unit
        return null
    }

    private fun availableFieldsHint(fields: List<FieldPlan>): String =
        if (fields.isEmpty()) "<none>" else fields.joinToString(", ") { it.name }
}
