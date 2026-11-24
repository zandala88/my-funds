package com.myfunds.core

object MarketCache {
    fun mergeQuotes(previous: Map<String, FundQuote>, incoming: List<FundQuote>): Map<String, FundQuote> = previous.toMutableMap().apply {
        incoming.forEach { q ->
            val old = get(q.code)
            if (q.nav != null && q.date != null && q.date >= (old?.date ?: "")) {
                put(q.code, if (old?.date == q.date) q.copy(change = q.change ?: old.change,
                    estimate = q.estimate ?: old.estimate,
                    estimateChange = if (q.estimate == null) old.estimateChange else q.estimateChange,
                    estimateTime = if (q.estimate == null) old.estimateTime else q.estimateTime) else q)
            }
        }
    }
    fun mergeIndices(previous: List<IndexQuote>, incoming: List<IndexQuote>): List<IndexQuote> = previous.associateBy { it.code }.toMutableMap().apply {
        incoming.filter { it.price != null && it.change != null }.forEach { put(it.code, it) }
    }.values.toList()
}

class LatestRequest {
    private var generation = 0L
    fun next(): Long = ++generation
    fun isCurrent(token: Long): Boolean = generation == token
}
