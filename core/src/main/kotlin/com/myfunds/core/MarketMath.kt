package com.myfunds.core

import java.math.BigDecimal
import java.math.RoundingMode

data class DailyProfit(val amount: BigDecimal, val estimated: Boolean)
object MarketMath {
    fun portfolioDailies(p: Portfolio, quotes: Map<String, FundQuote>, today: String): List<DailyProfit?> {
        val grouped = p.trades.groupBy { it.holdingId }
        return p.holdings.map { h -> portfolioDaily(p.copy(trades = grouped[h.id].orEmpty()), h, quotes[h.code], today) }
    }
    fun portfolioDaily(p: Portfolio, h: Holding, q: FundQuote?, today: String): DailyProfit? {
        val trades = p.trades.filter { it.holdingId == h.id }
        if (trades.any { it.kind == "CALIBRATE" && it.date >= today }) return null
        val earlierCalibration = trades.any { it.kind == "CALIBRATE" && it.date < today }
        val knownBaseline = h.confirmedDate?.let { it < today || (it == today && h.initialShares?.signum() == 0) } == true
        if (!knownBaseline && !earlierCalibration) return if (h.shares.signum() == 0 && trades.isEmpty()) DailyProfit(BigDecimal.ZERO, false) else null
        var opening = h.shares
        trades.filter { it.date >= today }.forEach { t ->
            opening = when (t.kind) { "BUY" -> opening - t.shares; "SELL" -> opening + t.shares; else -> opening }
        }
        if (opening.signum() < 0) return null
        return daily(h.copy(shares = opening), q, today)
    }
    private fun precedingSession(date: String?, today: String): Boolean = runCatching {
        val start = java.time.LocalDate.parse(date)
        val end = java.time.LocalDate.parse(today)
        val gap = java.time.temporal.ChronoUnit.DAYS.between(start, end)
        gap == 1L || (gap == 3L && start.dayOfWeek == java.time.DayOfWeek.FRIDAY && end.dayOfWeek == java.time.DayOfWeek.MONDAY)
    }.getOrDefault(false)
    fun usableEstimate(q: FundQuote?, today: String): FundQuote? = q?.takeIf {
        it.estimateVerified != false && it.kind !in listOf(FundKind.MONEY, FundKind.QDII) && it.currency == "CNY" &&
            !it.name.contains("QDII", true) && !it.name.contains("海外") && precedingSession(it.date, today) && it.nav?.signum() == 1 && it.date != null && runCatching { java.time.LocalDate.parse(it.date) }.isSuccess && it.date < today && it.estimate?.signum() == 1 && it.estimateTime?.take(10) == today && runCatching { java.time.LocalDateTime.parse(it.estimateTime.replace(' ', 'T')) }.isSuccess
    }
    fun daily(h: Holding, q: FundQuote?, today: String): DailyProfit? {
        if (h.shares.signum() == 0) return DailyProfit(BigDecimal.ZERO, false)
        q ?: return null
        val market = q.model?.takeIf { q.kind in listOf(FundKind.ETF, FundKind.REIT) && it.method == EstimateMethod.MARKET &&
            it.date == today && it.complete && it.basisPrice.signum() > 0 && it.currency == q.currency && it.change > BigDecimal("-100") }
        if (market != null) return DailyProfit((market.basisPrice * market.change.movePointLeft(2) * h.shares).setScale(2, RoundingMode.HALF_UP), false)
        val nav = q.nav
        val income = q.incomePerTenThousand
        if (q.kind == FundKind.MONEY && q.date == today && income != null) {
            return DailyProfit((income.movePointLeft(4) * h.shares).setScale(2, RoundingMode.HALF_UP), false)
        }
        val change = q.change
        if (q.kind != FundKind.MONEY && nav != null && q.date == today && change != null && change > BigDecimal("-100")) {
            val previous = nav.divide(BigDecimal.ONE + change.movePointLeft(2), 18, RoundingMode.HALF_UP)
            return DailyProfit(((nav - previous) * h.shares).setScale(2, RoundingMode.HALF_UP), false)
        }
        val estimate = usableEstimate(q, today)?.estimate
        if (estimate != null && nav != null) return DailyProfit(((estimate - nav) * h.shares).setScale(2, RoundingMode.HALF_UP), true)
        val model = q.model?.takeIf { it.date == today && it.complete && it.basisPrice.signum() > 0 && it.currency == q.currency && it.change > BigDecimal("-100") } ?: return null
        return DailyProfit((model.basisPrice * model.change.movePointLeft(2) * h.shares).setScale(2, RoundingMode.HALF_UP), true)
    }
}
