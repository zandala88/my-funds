package com.myfunds.android

import com.myfunds.core.FundQuote
import com.myfunds.core.Holding
import com.myfunds.core.MarketMath
import java.math.BigDecimal
import java.time.LocalDate

internal data class DisplayedDailyProfit(
    val amount: BigDecimal,
    val estimated: Boolean,
    val date: String,
    val change: BigDecimal?,
    val asOf: String
) {
    fun label(today: String): String = when {
        estimated -> "今日估算"
        date == today -> "今日收益"
        date == LocalDate.parse(today).minusDays(1).toString() -> "昨日收益"
        else -> "$date 收益"
    }
}

internal object DailyProfitPresentation {
    fun quoteAsOf(q: FundQuote?, today: String): String? {
        val navDate = q?.date?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: return null
        val nav = q.nav ?: return null
        if (nav.signum() <= 0 || navDate > today) return null
        return if (MarketMath.usableEstimate(q, today) != null) "估值时间 ${q.estimateTime}" else "净值日期 $navDate"
    }

    fun forHolding(h: Holding, q: FundQuote?, today: String): DisplayedDailyProfit? {
        val asOf = quoteAsOf(q, today) ?: return null
        val navDate = q?.date ?: return null
        val date = if (MarketMath.usableEstimate(q, today) != null) today else navDate
        val result = MarketMath.daily(h, q, date) ?: return null
        // A zero-share position also needs the quote's date/estimate provenance.
        val estimated = date == today && navDate < today
        return DisplayedDailyProfit(result.amount, estimated, date,
            if (estimated) q.estimateChange else q.change,
            asOf)
    }
}
