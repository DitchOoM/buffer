package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError

/**
 * Detects recursive `@ProtocolMessage` cycles in the dependency graph.
 *
 * Edges:
 *  - `Plan.Leaf` field with [FieldStrategy.NestedMessage] → that nested codec's plan.
 *  - `Plan.Sealed_` → each variant's plan, plus the discriminator's class when it
 *    is a `@ProtocolMessage` itself.
 *
 * A direct or transitive cycle here would generate a codec that calls into itself
 * (`ACodec.decode → BCodec.decode → ACodec.decode → ...`) and stack-overflow at runtime
 * — the kind of failure the legacy generator silently produced. PhaseC catches it
 * with a DFS over the plan graph; the diagnostic names every class on the cycle so
 * the user sees the ring directly.
 */
internal object CycleDetector {
    fun check(plans: Map<TypeFqn, Plan>): List<KspError> {
        val codecToFqn = plans.values.associateBy { it.decl }
        val errors = mutableListOf<KspError>()
        val visited = mutableSetOf<TypeFqn>()
        val reportedCycles = mutableSetOf<List<TypeFqn>>()
        for (root in plans.keys) {
            if (root in visited) continue
            dfs(root, codecToFqn, visited, mutableListOf(), mutableSetOf(), reportedCycles, errors)
        }
        return errors
    }

    private fun dfs(
        current: TypeFqn,
        plans: Map<TypeFqn, Plan>,
        visited: MutableSet<TypeFqn>,
        stack: MutableList<TypeFqn>,
        onStack: MutableSet<TypeFqn>,
        reportedCycles: MutableSet<List<TypeFqn>>,
        errors: MutableList<KspError>,
    ) {
        stack.add(current)
        onStack.add(current)
        val plan = plans[current]
        if (plan != null) {
            for (next in dependencies(plan)) {
                if (next == current) {
                    reportSelfCycle(current, reportedCycles, errors)
                    continue
                }
                if (next in onStack) {
                    val cycle = stack.dropWhile { it != next } + next
                    val key = canonicalCycleKey(cycle)
                    if (reportedCycles.add(key)) {
                        errors += cycleError(cycle)
                    }
                    continue
                }
                if (next !in visited) {
                    dfs(next, plans, visited, stack, onStack, reportedCycles, errors)
                }
            }
        }
        onStack.remove(current)
        stack.removeAt(stack.lastIndex)
        visited.add(current)
    }

    private fun dependencies(plan: Plan): List<TypeFqn> =
        when (plan) {
            is Plan.Object_ -> emptyList()
            is Plan.Leaf -> nestedFromFields(plan.fields)
            is Plan.Sealed_ ->
                plan.variants.map { it.decl } +
                    plan.variants.flatMap { nestedFromFields(it.fields) }
        }

    private fun nestedFromFields(fields: List<FieldPlan>): List<TypeFqn> =
        fields.mapNotNull { f ->
            when (val s = f.strategy) {
                is FieldStrategy.NestedMessage -> TypeFqn(classNameToFqn(s.codec))
                else -> null
            }
        }

    private fun classNameToFqn(cn: com.squareup.kotlinpoet.ClassName): String {
        val pkg = cn.packageName
        val simple = cn.simpleNames.joinToString(".")
        // generated codec FQNs end in "Codec"; convert back to user FQN by trimming.
        val source =
            if (simple.endsWith("Codec")) {
                simple.removeSuffix("Codec")
            } else {
                simple
            }
        return if (pkg.isEmpty()) source else "$pkg.$source"
    }

    private fun reportSelfCycle(
        current: TypeFqn,
        reportedCycles: MutableSet<List<TypeFqn>>,
        errors: MutableList<KspError>,
    ) {
        val key = canonicalCycleKey(listOf(current, current))
        if (reportedCycles.add(key)) {
            errors +=
                KspError(
                    message =
                        "Recursive @ProtocolMessage cycle: '${current.canonical}' references itself. " +
                            "Use @UseCodec to break the cycle, or restructure the types.",
                    sourceFqn = current.canonical,
                )
        }
    }

    private fun cycleError(cycle: List<TypeFqn>): KspError {
        val display = cycle.joinToString(" → ") { "'${it.canonical}'" }
        val first = cycle.first().canonical
        return KspError(
            message =
                "Recursive @ProtocolMessage cycle: $display. Break the cycle with @UseCodec on " +
                    "one of the offending fields, or restructure the types so the dependency graph " +
                    "is acyclic.",
            sourceFqn = first,
        )
    }

    private fun canonicalCycleKey(cycle: List<TypeFqn>): List<TypeFqn> {
        if (cycle.isEmpty()) return cycle
        // Drop the trailing repeat of the cycle's start node before normalizing.
        val ring = if (cycle.size > 1 && cycle.first() == cycle.last()) cycle.dropLast(1) else cycle
        if (ring.isEmpty()) return ring
        var minIdx = 0
        for (i in 1 until ring.size) {
            if (ring[i].canonical < ring[minIdx].canonical) minIdx = i
        }
        return (ring.drop(minIdx) + ring.take(minIdx))
    }
}
