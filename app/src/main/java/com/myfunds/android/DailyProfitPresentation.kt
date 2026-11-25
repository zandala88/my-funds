package com.myfunds.android

import com.myfunds.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class DisplayedDailyProfit(
    val amount: BigDecimal, val estimated: Boolean, val date: String, val change: BigDecimal?, val asOf: String,
    val model: FundEstimate? = null, val currency: String = "CNY", val complete: Boolean = true, val accountingOnly: Boolean = false
) {
    val includeInTotal get() = complete && currency == "CNY" && !accountingOnly
    fun label(today: String): String {
        val prefix = when (date) { today -> "今日"; LocalDate.parse(today).minusDays(1).toString() -> "昨日"; else -> "$date " }
        return prefix + when {
            !complete -> "部分持仓贡献"
            accountingOnly && estimated -> "净值估算"
            accountingOnly -> "净值收益"
            model?.method == EstimateMethod.MARKET -> "场内收益"
            model?.method == EstimateMethod.MONEY -> "收益预测"
            model != null -> "模型估算"
            estimated -> "估算"
            else -> "收益"
        }
    }
    fun ledgerLabel(today: String) = if (model == null && estimated && date == today) "今日估算收益" else label(today)
    fun provenance(): String? = model?.let {
        "${it.source} · 直接行情覆盖 ${it.directCoverage.stripTrailingZeros().toPlainString()}% · 模型覆盖 ${it.modeledCoverage.stripTrailingZeros().toPlainString()}% · 可信度 ${it.confidence}" +
            (it.disclosureDate?.let { date -> " · 持仓披露 $date" } ?: "")
    }
}

internal object DailyProfitPresentation {
    private fun validDate(date: String?) = date?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
    private fun published(q: FundQuote, date: String) = q.date == date && q.nav?.signum() == 1 &&
        (if (q.kind == FundKind.MONEY) q.incomePerTenThousand != null else q.change?.let { it > BigDecimal("-100") } == true)
    private fun model(q: FundQuote, today: String): FundEstimate? = q.model?.takeIf { m ->
        val listedMarket = q.kind in listOf(FundKind.ETF, FundKind.REIT) && m.method == EstimateMethod.MARKET
        val basisDate = m.basisDate
        val navDate = q.date
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(m.date), LocalDate.parse(today)) }.getOrNull()
        age != null && age in 0..7 && m.basisPrice.signum() > 0 && m.change > BigDecimal("-100") && m.currency == q.currency &&
            m.directCoverage in BigDecimal.ZERO..BigDecimal("100") && m.modeledCoverage in BigDecimal.ZERO..BigDecimal("100") &&
            (basisDate == null || (validDate(basisDate) != null && basisDate <= m.date)) &&
            (listedMarket || (!published(q, today) && MarketMath.usableEstimate(q, today) == null)) &&
            (listedMarket || navDate == null || m.date > navDate || (m.date == navDate && !published(q, m.date)))
    }
    fun quoteAsOf(q: FundQuote?, today: String): String? {
        q ?: return null
        model(q, today)?.let { return "${if (it.method == EstimateMethod.MARKET) "场内行情" else "模型行情"} ${it.asOf}" }
        if (published(q, today)) return if (q.kind == FundKind.MONEY) "万份收益日期 ${q.date}" else "净值日期 ${q.date}"
        if (MarketMath.usableEstimate(q, today) != null) return "估值时间 ${q.estimateTime}"
        val date = validDate(q.date)?.takeIf { it <= today } ?: return null
        return if (q.nav?.signum() == 1) "净值日期 $date" else null
    }
    fun forHolding(h: Holding, q: FundQuote?, today: String): DisplayedDailyProfit? {
        q ?: return null
        val local = model(q, today)
        if (local != null) {
            val amount = (local.basisPrice * local.change.movePointLeft(2) * h.shares).setScale(2, RoundingMode.HALF_UP)
            return DisplayedDailyProfit(amount, local.method != EstimateMethod.MARKET, local.date, local.change,
                quoteAsOf(q, today) ?: local.asOf, local, q.currency, local.complete)
        }
        val asOf = quoteAsOf(q, today) ?: return null
        val navDate = validDate(q.date) ?: return null
        val date = if (MarketMath.usableEstimate(q, today) != null) today else navDate
        val result = MarketMath.daily(h, q.copy(model = null), date) ?: return null
        val estimated = date == today && navDate < today
        val change = if (estimated) q.estimateChange else if (q.kind == FundKind.MONEY) q.incomePerTenThousand?.movePointLeft(2) else q.change
        return DisplayedDailyProfit(result.amount, estimated, date, change, asOf, currency = q.currency, accountingOnly = q.kind in listOf(FundKind.ETF, FundKind.REIT))
    }
}
