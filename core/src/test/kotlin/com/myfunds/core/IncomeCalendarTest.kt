package com.myfunds.core

import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class IncomeCalendarTest {
    private fun d(s: String) = BigDecimal(s)
    private val h = Holding("h", "000001", "测试基金", shares = d("100"), cost = d("1"), confirmedDate = "2026-11-20", initialShares = d("100"))
    private val p = Portfolio(holdings = listOf(h))
    private val point = DailyNav(h.code, "2026-11-24", d("1.02"), previousNav = d("1"), referenceDate = "2026-11-23", change = d("2"))
    private fun series(vararg rows: DailyNav) = NavHistory(h.code, "2026-11-01", "2026-11-25", rows.toList(), true)
    private fun calculated(p: Portfolio = this.p, row: DailyNav = point) = IncomeCalendar.fromHistory(p, p.holdings.single(), series(row), YearMonth.of(2026,11), TradingCalendar.builtin())
        .single { it.date == row.date }
    private fun trade(id: String, kind: String, shares: String, date: String, fee: String = "0", price: String = "1") =
        Trade(id, h.id, kind, d(shares), d(price), d(fee), date)

    @Test fun openingSharesReplayBuysSellsDcaAndCalibrationsInDateOrder() {
        val trades = listOf(trade("a", "BUY", "10", "2026-11-23"), trade("b", "DCA", "20", "2026-11-23"), trade("c", "SELL", "5", "2026-11-24"))
        assertEquals(d("130"), HoldingTimeline.opening(p.copy(trades = trades), h, "2026-11-24").shares)
        assertEquals(d("125"), HoldingTimeline.opening(p.copy(trades = trades), h, "2026-11-25").shares)
        val calibrated = p.copy(trades = trades + trade("d", "CALIBRATE", "200", "2026-11-25"))
        assertNull(HoldingTimeline.opening(calibrated, h, "2026-11-25").shares)
        assertEquals(d("200"), HoldingTimeline.opening(calibrated, h, "2026-11-26").shares)
        assertEquals(d("130"), HoldingTimeline.opening(calibrated, h, "2026-11-24").shares)
    }
    @Test fun unknownAndSameDayPositiveInitialSnapshotsAreNotInventedHistory() {
        assertNull(HoldingTimeline.opening(p, h, "2026-11-20").shares)
        assertNull(HoldingTimeline.opening(p, h.copy(confirmedDate = null, initialShares = null), "2026-11-24").shares)
        assertEquals(BigDecimal.ZERO, HoldingTimeline.opening(p, h.copy(confirmedDate = "2026-11-24", initialShares = BigDecimal.ZERO), "2026-11-24").shares)
    }
    @Test fun sameDayBuyAndDcaEarnNoPriorRiseAndFeesReduceIncome() {
        val trades = listOf(trade("a", "BUY", "100", "2026-11-24", "0.10"), trade("b", "DCA", "100", "2026-11-24"))
        val result = calculated(p.copy(trades = trades))
        assertEquals(IncomeKind.CONFIRMED, result.kind)
        assertEquals(d("1.90"), result.amount)
        assertEquals(d("100"), result.shares)
    }
    @Test fun sameDaySaleRetainsOpeningSharesRiseAndDeductsSaleFee() {
        val result = calculated(p.copy(trades = listOf(trade("a", "SELL", "100", "2026-11-24", "0.20"))))
        assertEquals(d("1.80"), result.amount)
    }
    @Test fun calibrationDayIsUnknownAndDoesNotDestroyEarlierStoredIncome() {
        val earlier = calculated()
        val calibrated = p.copy(trades = listOf(trade("a", "CALIBRATE", "200", "2026-11-24")))
        val unknown = calculated(calibrated)
        assertEquals(IncomeKind.UNKNOWN, unknown.kind)
        assertNull(unknown.amount)
        val changed = IncomeCalendar.merge(p.copy(incomes = listOf(earlier)), listOf(unknown))
        assertEquals(IncomeKind.UNKNOWN, changed.incomes.single().kind)
    }
    @Test fun formalIncomeReplacesEstimateButTransientMissingDataCannotEraseFormal() {
        val formal = calculated()
        val estimate = formal.copy(kind = IncomeKind.ESTIMATE, amount = d("3"))
        val first = IncomeCalendar.merge(p.copy(incomes = listOf(estimate)), listOf(formal))
        assertEquals(formal, first.incomes.single())
        val missing = formal.copy(kind = IncomeKind.UNKNOWN, amount = null, note = "接口失败")
        assertEquals(formal, IncomeCalendar.merge(first, listOf(missing, estimate)).incomes.single())
    }
    @Test fun moneyIncomeIsPerTenThousandAndNotNetAssetPrice() {
        val row = point.copy(nav = BigDecimal.ONE, kind = FundKind.MONEY, incomePerTenThousand = d("0.5"), previousNav = null, change = BigDecimal.ZERO)
        assertEquals(d("0.01"), calculated(row = row).amount)
    }
    @Test fun missingNavOrUnresolvedCorporateActionsStayUnknown() {
        val missing = IncomeCalendar.fromHistory(p, h, series(), YearMonth.of(2026,11), TradingCalendar.builtin()).single { it.date == "2026-11-24" }
        assertEquals(IncomeKind.UNKNOWN, missing.kind)
        assertNull(missing.amount)
        assertEquals(IncomeKind.UNKNOWN, calculated(row = point.copy(change = null, corporateAction = true)).kind)
    }
    @Test fun weeklyDisclosureReturnIsNotAssignedToOneDay() {
        val weekly = point.copy(referenceDate = "2026-11-17", change = d("10"))
        assertEquals(IncomeKind.UNKNOWN, calculated(row = weekly).kind)
    }
    @Test fun estimatePartialAndCurrenciesAreNeverSummedAsFullConfirmedMonth() {
        val formal = calculated()
        val estimate = formal.copy(code = "000002", kind = IncomeKind.ESTIMATE, amount = d("3"))
        val partial = formal.copy(code = "000003", kind = IncomeKind.PARTIAL, amount = d("4"))
        val usd = formal.copy(code = "000004", currency = "USD", amount = d("99"))
        val summary = IncomeCalendar.summary(listOf(formal,estimate,partial,usd), "2026-11-01", "2026-11-30", "CNY")
        assertEquals(d("2.00"), summary.confirmed)
        assertEquals(d("3"), summary.estimated)
        assertEquals(d("4"), summary.partial)
        assertFalse(summary.complete)
        assertEquals(d("99"), IncomeCalendar.summary(listOf(usd), "2026-11-01", "2026-11-30", "USD").confirmed)
    }
    @Test fun removingFundKeepsIndependentHistoricIncomeSnapshots() {
        val result = PortfolioEditing.delete(p.copy(incomes = listOf(calculated())), setOf(h.id))
        assertTrue(result.holdings.isEmpty())
        assertEquals(1, result.incomes.size)
        assertEquals(result, BackupCodec.decode(BackupCodec.encode(result)))
    }
    @Test fun exactNavDifferenceWinsOverRoundedPublishedPercentage() {
        val large=h.copy(shares=d("100000"),initialShares=d("100000"))
        val result=calculated(p.copy(holdings=listOf(large)),point.copy(nav=d("1.2346"),previousNav=d("1.2345"),change=d("0.01")))
        assertEquals(d("10.00"),result.amount)
    }

    @Test fun schemaOneImportsWithoutCalendarAndNewFinancialRowsStayStrict() {
        val old=org.json.JSONObject(BackupCodec.encode(p)).put("schemaVersion",1).apply { remove("incomes"); remove("executions"); getJSONArray("holdings").getJSONObject(0).remove("dcaSchedule") }
        assertEquals(p,BackupCodec.decode(old.toString()))
        val repeated=p.copy(incomes=listOf(calculated(),calculated()))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(repeated) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(p.copy(incomes=listOf(calculated().copy(amount=null)))) }
    }

}
