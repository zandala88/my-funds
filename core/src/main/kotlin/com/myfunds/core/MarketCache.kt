package com.myfunds.core

object MarketCache {
    fun mergeQuotes(previous: Map<String, FundQuote>, incoming: List<FundQuote>): Map<String, FundQuote> = previous.toMutableMap().apply {
        incoming.forEach { q ->
            val old = get(q.code)
            if (q.nav != null && q.date != null && q.date >= (old?.date ?: "")) {
                put(q.code, if (old?.date == q.date) q.copy(change = q.change ?: old.change,
                    estimate = if (q.estimateVerified == false) null else q.estimate ?: old.estimate,
                    estimateChange = if (q.estimateVerified == false) null else if (q.estimate == null) old.estimateChange else q.estimateChange,
                    estimateTime = if (q.estimateVerified == false) null else if (q.estimate == null) old.estimateTime else q.estimateTime,
                    estimateVerified = q.estimateVerified ?: old.estimateVerified,
                    kind = if (q.kind == FundKind.UNKNOWN) old.kind else q.kind,
                    currency = if (q.kind == FundKind.UNKNOWN) old.currency else q.currency,
                    incomePerTenThousand = q.incomePerTenThousand ?: old.incomePerTenThousand,
                    model = q.model ?: if (q.estimateReason == null) old.model else null) else q)
            } else if (q.model != null || q.estimateReason != null) {
                // Market-price P&L is useful even when the NAV service has no row. Never erase a valid formal NAV.
                put(q.code, if (old == null) q else old.copy(
                    kind = q.kind.takeUnless { it == FundKind.UNKNOWN } ?: old.kind,
                    currency = if (q.kind != FundKind.UNKNOWN) q.currency else old.currency,
                    model = q.model, estimateReason = q.estimateReason,
                    estimate = if (q.estimateVerified == false) null else old.estimate,
                    estimateChange = if (q.estimateVerified == false) null else old.estimateChange,
                    estimateTime = if (q.estimateVerified == false) null else old.estimateTime,
                    estimateVerified = q.estimateVerified ?: old.estimateVerified))
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
