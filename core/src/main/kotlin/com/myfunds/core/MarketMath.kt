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
    fun usableEstimate(q: FundQuote?, today: String): FundQuote? = q?.takeIf {
        it.nav != null && it.date != null && it.date < today && it.estimate != null && it.estimateTime?.take(10) == today
    }
    fun daily(h: Holding, q: FundQuote?, today: String): DailyProfit? {
        if (h.shares.signum() == 0) return DailyProfit(BigDecimal.ZERO, false)
        val nav = q?.nav ?: return null
        val change = q.change
        if (q.date == today && change != null && change > BigDecimal("-100")) {
            val previous = nav.divide(BigDecimal.ONE + change.movePointLeft(2), 18, RoundingMode.HALF_UP)
            return DailyProfit(((nav - previous) * h.shares).setScale(2, RoundingMode.HALF_UP), false)
        }
        val estimate = usableEstimate(q, today)?.estimate ?: return null
        return DailyProfit(((estimate - nav) * h.shares).setScale(2, RoundingMode.HALF_UP), true)
    }
}
