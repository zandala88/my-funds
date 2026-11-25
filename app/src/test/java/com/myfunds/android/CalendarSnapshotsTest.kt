package com.myfunds.android

import com.myfunds.core.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class CalendarSnapshotsTest {
    private val h=Holding("h","000001","基金",shares=BigDecimal("100"),cost=BigDecimal.ONE,confirmedDate="2026-11-20",initialShares=BigDecimal("100"))
    private val income=IncomeEntry(h.id,h.code,h.name,h.groupId,"2026-11-24","CNY",IncomeKind.CONFIRMED,BigDecimal("2.00"),h.shares,"公布净值","2026-11-24")
    @Test fun editingInitialBaselineInvalidatesAffectedIncomeInsteadOfKeepingFalseFormalAmount() {
        val old=Portfolio(holdings=listOf(h),incomes=listOf(income))
        val edited=old.copy(holdings=listOf(h.copy(confirmedDate="2026-11-24",shares=BigDecimal("200"),initialShares=BigDecimal("200"))))
        val updated=CalendarSnapshots.afterEdit(old,edited)
        assertEquals(IncomeKind.UNKNOWN,updated.incomes.single().kind); assertNull(updated.incomes.single().amount)
    }
    @Test fun deletingHoldingDoesNotEraseHistoryAndCostOnlyEditKeepsIncome() {
        val old=Portfolio(holdings=listOf(h),incomes=listOf(income))
        assertEquals(listOf(income),CalendarSnapshots.afterEdit(old,old.copy(holdings=emptyList())).incomes)
        assertEquals(listOf(income),CalendarSnapshots.afterEdit(old,old.copy(holdings=listOf(h.copy(cost=BigDecimal.TEN)))).incomes)
    }
    @Test fun sameDayDcaIsExcludedFromDisplayOpeningSharesAndFeesAreDeducted() {
        val trade=Trade("d",h.id,"DCA",BigDecimal("100"),BigDecimal.ONE,BigDecimal("0.20"),"2026-11-24")
        val p=Portfolio(holdings=listOf(h.copy(shares=BigDecimal("200"))),trades=listOf(trade))
        val q=FundQuote(h.code,h.name,BigDecimal("1.02"),"2026-11-24",change=BigDecimal("2"))
        assertEquals(BigDecimal("1.80"),CalendarSnapshots.display(p,p.holdings.single(),q,"2026-11-24")!!.amount)
    }
}
