package com.ditchoom.buffer.codec.processor

data class BatchGroup(
    val fields: List<FieldInfo>,
    val totalBytes: Int,
    val readMethod: String, // "readLong", "readInt", "readShort"
) {
    init {
        require(fields.size >= 2) {
            "BatchGroup requires at least 2 fields, got ${fields.size}"
        }
        val expectedBytes = fields.sumOf { it.strategy.fixedSize }
        require(totalBytes == expectedBytes) {
            "totalBytes ($totalBytes) does not match sum of field sizes ($expectedBytes)"
        }
        require(readMethod in setOf("readShort", "readInt", "readLong")) {
            "readMethod must be readShort, readInt, or readLong, got '$readMethod'"
        }
    }
}

sealed interface CodegenItem {
    data class Batched(
        val group: BatchGroup,
    ) : CodegenItem

    data class Single(
        val field: FieldInfo,
    ) : CodegenItem
}

class BatchOptimizer {
    fun optimize(fields: List<FieldInfo>): List<CodegenItem> {
        val result = mutableListOf<CodegenItem>()
        val currentBatch = mutableListOf<FieldInfo>()
        var currentBatchSize = 0

        fun flushBatch() {
            while (currentBatch.size >= 2) {
                // Find the largest aligned prefix (total = 2, 4, or 8)
                var prefixSize = 0
                var prefixCount = 0
                var bestCount = 0
                var bestSize = 0
                for (i in currentBatch.indices) {
                    prefixSize += currentBatch[i].strategy.fixedSize
                    prefixCount = i + 1
                    if (prefixCount >= 2 && prefixSize in setOf(2, 4, 8)) {
                        bestCount = prefixCount
                        bestSize = prefixSize
                    }
                    if (prefixSize >= 8) break
                }
                if (bestCount >= 2) {
                    val readMethod =
                        when (bestSize) {
                            2 -> "readShort"
                            4 -> "readInt"
                            8 -> "readLong"
                            else -> error("unreachable")
                        }
                    result.add(CodegenItem.Batched(BatchGroup(currentBatch.subList(0, bestCount).toList(), bestSize, readMethod)))
                    val remaining = currentBatch.subList(bestCount, currentBatch.size).toList()
                    currentBatch.clear()
                    currentBatch.addAll(remaining)
                    currentBatchSize = remaining.sumOf { it.strategy.fixedSize }
                } else {
                    // No aligned prefix found; emit first field individually and retry
                    val removed = currentBatch.removeFirst()
                    currentBatchSize -= removed.strategy.fixedSize
                    result.add(CodegenItem.Single(removed))
                }
            }
            // Emit any remaining single field
            for (field in currentBatch) {
                result.add(CodegenItem.Single(field))
            }
            currentBatch.clear()
            currentBatchSize = 0
        }

        for (field in fields) {
            // Conditional fields break batches
            if (field.condition != null) {
                flushBatch()
                result.add(CodegenItem.Single(field))
                continue
            }

            val fieldSize = field.strategy.fixedSize
            if (fieldSize < 0 || field.strategy is FieldReadStrategy.Custom) {
                // Variable-length or custom field, break batch
                flushBatch()
                result.add(CodegenItem.Single(field))
                continue
            }

            // Check if adding this field would exceed 8 bytes
            if (currentBatchSize + fieldSize > 8) {
                flushBatch()
            }

            currentBatch.add(field)
            currentBatchSize += fieldSize
        }
        flushBatch()

        return result
    }
}
