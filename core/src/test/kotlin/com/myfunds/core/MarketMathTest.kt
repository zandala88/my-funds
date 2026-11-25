package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MarketMathTest {
    private val h = Holding("h", "000001", "测试", shares = BigDecimal("100"), cost = BigDecimal.ONE)
    @Test fun estimateUsesPreviousPublishedNavOnly() {
        val q = FundQuote("000001", "测试", BigDecimal("1.00"), "2026-11-23", estimate = BigDecimal("1.02"), estimateTime = "2026-11-24 14:00")
        assertEquals(0, BigDecimal("2.00").compareTo(MarketMath.daily(h, q, "2026-11-24")!!.amount))
        assertTrue(MarketMath.daily(h, q, "2026-11-24")!!.estimated)
        assertNull(MarketMath.daily(h, q, "2026-11-25"))
    }
    @Test fun publishedNavTakesPriorityOverSameDayEstimate() {
        val q = FundQuote("000001", "测试", BigDecimal("1.02"), "2026-11-24", change = BigDecimal("2"), estimate = BigDecimal("1.01"), estimateTime = "2026-11-24 14:00")
        val daily = MarketMath.daily(h, q, "2026-11-24")!!
        assertEquals(0, BigDecimal("2.00").compareTo(daily.amount))
        assertFalse(daily.estimated)
        assertNull(MarketMath.usableEstimate(q, "2026-11-24"))
    }
    @Test fun inconsistentTimeAndInvalidChangeNeverProduceFakeYield() {
        val q = FundQuote("000001", "测试", BigDecimal("1.02"), "2026-11-24", change = BigDecimal("-100"))
        assertNull(MarketMath.daily(h, q, "2026-11-24"))
        assertNull(MarketMath.daily(h, q.copy(date = null, estimate = BigDecimal("1.03"), estimateTime = "2026-11-24 14:00"), "2026-11-24"))
    }
    @Test fun malformedEstimateClockIsNotAUsableIntradayQuote() {
        val q = FundQuote("000001", "测试", BigDecimal.ONE, "2026-11-24", estimate = BigDecimal("1.02"), estimateTime = "2026-11-25 99:99")
        assertNull(MarketMath.usableEstimate(q, "2026-11-25"))
    }

    @Test fun missingMoneyIncomeDoesNotBecomeZeroNavChangeOrUseIncomeAsPriceEstimate() {
        val q = FundQuote("000198", "货币", BigDecimal.ONE, "2026-11-25", change = BigDecimal.ZERO, kind = FundKind.MONEY,
            estimate = BigDecimal("0.5"), estimateTime = "2026-11-25 14:00")
        assertNull(MarketMath.daily(h, q, "2026-11-25"))
        assertNull(MarketMath.usableEstimate(q.copy(date = "2026-11-24"), "2026-11-25"))
        val model = FundEstimate("2026-11-25", BigDecimal("0.005"), BigDecimal.ONE, "2026-11-25", "依据 2026-11-25", EstimateMethod.MONEY,
            BigDecimal.ZERO, BigDecimal("100"), true, null, "年化预测")
        assertEquals(BigDecimal("0.01"), MarketMath.daily(h, q.copy(model = model), "2026-11-25")!!.amount)
        assertTrue(MarketMath.daily(h, q.copy(model = model), "2026-11-25")!!.estimated)
    }

    @Test fun multiDayNavGapAndUnverifiedOverseasProviderCannotBecomeTodayProfit() {
        val q = FundQuote("000001", "测试", BigDecimal.ONE, "2026-11-23", estimate = BigDecimal("1.05"), estimateTime = "2026-11-25 14:00")
        assertNull(MarketMath.usableEstimate(q, "2026-11-25"))
        assertNotNull(MarketMath.usableEstimate(q.copy(date = "2026-11-20", estimateTime = "2026-11-23 14:00"), "2026-11-23"))
        assertNull(MarketMath.usableEstimate(q.copy(date = "2026-11-24", kind = FundKind.QDII), "2026-11-25"))
    }

}
