package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class DcaBudgetTest {
    private fun d(s: String) = BigDecimal(s)
    private val base = Holding("h", "000001", "测试指数A")
    @Test fun dailyBudgetTotalsAmountAndSharePlansAcrossGroups() {
        val holdings = listOf(base.copy(dcaAmount = d("10.00")),
            base.copy(id = "h2", code = "000002", groupId = "another", dcaShares = d("3.00")),
            base.copy(id = "h3", code = "000003", dcaAmount = d("5.00"), dcaWeekdaysOnly = false),
            base.copy(id = "h4", code = "000004"))
        val quotes = mapOf("000002" to FundQuote("000002", "测试指数B", d("1.2500"), "2026-11-24"))
        val budget = DcaBudget.daily(holdings, quotes)
        assertEquals(3, budget.plans)
        assertEquals(0, budget.missingPrices)
        assertEquals(0, d("18.75").compareTo(budget.total))
    }
    @Test fun missingNavNeverBecomesZeroOrCostDisguisedAsTotal() {
        val budget = DcaBudget.daily(listOf(base.copy(cost = d("99"), dcaShares = d("100")),
            base.copy(id = "h2", code = "000002", dcaAmount = d("20.00"))), emptyMap())
        assertNull(budget.total)
        assertEquals(d("20.00"), budget.knownAmount)
        assertEquals(1, budget.missingPrices)
    }
    @Test fun noPlansIsZeroAndSharePlanTakesPrecedenceLikeExistingExecution() {
        assertEquals(BigDecimal.ZERO, DcaBudget.daily(listOf(base), emptyMap()).total)
        val quotes = mapOf("000001" to FundQuote("000001", "测试指数A", d("2"), "2026-11-24"))
        val budget = DcaBudget.daily(listOf(base.copy(dcaShares = d("5"), dcaAmount = d("999"))), quotes)
        assertEquals(0, d("10").compareTo(budget.total))
    }
    @Test fun unavailableNonPositiveNavRemainsUnknown() {
        val quote = FundQuote("000001", "测试指数A", BigDecimal.ZERO, "2026-11-24")
        assertNull(DcaBudget.daily(listOf(base.copy(dcaShares = d("5"))), mapOf("000001" to quote)).total)
    }
    @Test fun todayBudgetExcludesPausedAndWrongWeekdayPlansAndSeparatesCurrencies() {
        val weekly=DcaSchedule(DcaFrequency.WEEKLY,"2026-11-01",weekDay=3,revision="w")
        val p=Portfolio(holdings=listOf(base.copy(dcaAmount=d("10"),dcaSchedule=weekly),
            base.copy(id="usd",code="000002",dcaAmount=d("20"),dcaSchedule=weekly.copy(currency="USD")),
            base.copy(id="paused",code="000003",dcaAmount=d("50"),dcaSchedule=weekly.copy(paused=true)),
            base.copy(id="otherDay",code="000004",dcaAmount=d("99"),dcaSchedule=weekly.copy(weekDay=4))))
        val result=DcaBudget.onDate(p,emptyMap(),"2026-11-25",TradingCalendar.builtin())
        assertEquals(d("10"),result["CNY"]!!.total); assertEquals(d("20"),result["USD"]!!.total)
        assertEquals(1,result["CNY"]!!.plans)
    }

}
