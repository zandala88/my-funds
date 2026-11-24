package com.myfunds.core

/** Each candidate remains a snapshot; batch import never sums holding quantities. */
data class BatchHoldingCandidate(val candidate: HoldingCandidate, val screenshots: List<Int>, val conflict: Boolean)
object OcrBatch {
    const val MAX_IMAGES = 12
    const val TEXT_SEPARATOR = "\n—— 下一张截图 ——\n"
    fun parseText(text: String): List<BatchHoldingCandidate> = merge(text.split(TEXT_SEPARATOR).map { HoldingsOcr.parse(it) })
    fun merge(screenshots: List<List<HoldingCandidate>>): List<BatchHoldingCandidate> {
        require(screenshots.size in 1..MAX_IMAGES) { "一次最多选择 $MAX_IMAGES 张截图" }
        val merged = linkedMapOf<List<Any?>, BatchHoldingCandidate>()
        screenshots.forEachIndexed { index, candidates ->
            candidates.forEachIndexed { position, c ->
                // A name alone may be truncated or omit a share class. Never guess its identity.
                val identity = c.code?.takeIf { it.matches(Regex("[0-9]{6}")) } ?: "unknown:$index:$position"
                val key = listOf(identity, c.shares?.stripTrailingZeros(), c.cost?.stripTrailingZeros(),
                    c.amount?.stripTrailingZeros(), c.profit?.stripTrailingZeros(), c.confirmedDate)
                val previous = merged[key]
                merged[key] = if (previous == null) BatchHoldingCandidate(c, listOf(index + 1), false)
                    else previous.copy(screenshots = (previous.screenshots + (index + 1)).distinct())
            }
        }
        require(merged.size <= 50) { "本批超过 50 条持仓，请减少截图后分批导入" }
        val counts = merged.values.mapNotNull { it.candidate.code }.groupingBy { it }.eachCount()
        return merged.values.map { row -> row.copy(conflict = (counts[row.candidate.code] ?: 0) > 1) }
    }
}
