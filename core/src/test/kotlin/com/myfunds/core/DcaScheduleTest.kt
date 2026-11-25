package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class DcaScheduleTest {
    private fun d(s: String) = BigDecimal(s)
    private val cal = TradingCalendar.builtin()
    private fun h(schedule: DcaSchedule) = Holding("h","000001","测试基金",shares=d("100"),cost=d("1"),confirmedDate="2026-01-01",initialShares=d("100"),dcaAmount=d("10"),dcaSchedule=schedule)
    private fun daily(start: String = "2026-11-23") = DcaSchedule(DcaFrequency.TRADING_DAY,start,revision="v1")
    private fun prices(vararg dates: String) = dates.map { DailyNav("000001",it,d("2")) }
    private fun advance(p: Portfolio, day: String = "2026-11-24", points: List<DailyNav> = prices("2026-11-23","2026-11-24")) = DcaEngine.advance(p,day,points,cal).portfolio

    @Test fun weeklyHolidayShiftsWithoutChangingOriginalOccurrenceIdentity() {
        val schedule = DcaSchedule(DcaFrequency.WEEKLY,"2026-09-01",weekDay=5,revision="v1")
        val events = DcaSchedules.occurrences(h(schedule),"2026-09-25","2026-09-28",cal)
        assertEquals(1,events.size)
        assertEquals("2026-09-25",events.single().plannedDate)
        assertEquals("2026-09-28",events.single().executionDate)
    }
    @Test fun monthlyDay31ClampsToMonthEndThenShiftsAndHandlesLeapYear() {
        val schedule = DcaSchedule(DcaFrequency.MONTHLY,"2026-01-01",monthDay=31,revision="v1")
        val events = DcaSchedules.occurrences(h(schedule),"2026-02-01","2026-03-02",cal)
        assertEquals("2026-02-28",events.single().plannedDate)
        assertEquals("2026-03-02",events.single().executionDate)
        val leap = schedule.copy(startDate="2024-01-01",holidayPolicy=HolidayPolicy.KEEP)
        assertEquals("2024-02-29",DcaSchedules.occurrences(h(leap),"2024-02-01","2024-02-29",cal).single().plannedDate)
    }
    @Test fun tradingDayPlanDoesNotCreateMultipleShiftedWeekendPurchases() {
        val events = DcaSchedules.occurrences(h(daily("2026-11-20")),"2026-11-20","2026-11-24",cal)
        assertEquals(listOf("2026-11-20","2026-11-23","2026-11-24"),events.map { it.executionDate })
        val calendarDays = daily().copy(frequency=DcaFrequency.DAILY,holidayPolicy=HolidayPolicy.KEEP,startDate="2026-11-21")
        assertEquals(2,DcaSchedules.occurrences(h(calendarDays),"2026-11-21","2026-11-22",cal).size)
    }
    @Test fun explicitSkipAndUnknownYearRemainVisibleStates() {
        val weekly = daily("2026-09-01").copy(frequency=DcaFrequency.WEEKLY,weekDay=5,holidayPolicy=HolidayPolicy.SKIP)
        assertEquals(DcaStatus.SKIPPED,DcaSchedules.occurrences(h(weekly),"2026-09-25","2026-09-25",cal).single().status)
        val unknown = daily("2027-01-01")
        assertEquals(DcaStatus.WAITING_CALENDAR,DcaSchedules.occurrences(h(unknown),"2027-01-04","2027-01-04",cal).single().status)
    }
    @Test fun startEndAndPausedPlansDoNotGenerateExtraOccurrences() {
        val schedule = daily().copy(endDate="2026-11-24")
        assertEquals(2,DcaSchedules.occurrences(h(schedule),"2026-11-20","2026-11-30",cal).size)
        assertTrue(DcaSchedules.occurrences(h(schedule.copy(paused=true)),"2026-11-20","2026-11-30",cal).isEmpty())
    }
    @Test fun matchingFormalPricesAreRequiredAndRetryIsIdempotent() {
        val p = Portfolio(holdings=listOf(h(daily())))
        val missing = advance(p,points=prices("2026-11-24"))
        assertTrue(missing.trades.isEmpty())
        assertEquals(DcaStatus.WAITING_NAV,missing.executions.first().status)
        val posted = advance(missing)
        assertEquals(2,posted.trades.size)
        assertEquals(d("110.00000000"),posted.holdings.single().shares)
        assertTrue(posted.executions.all { it.status==DcaStatus.RECORDED })
        assertEquals(posted,advance(posted))
        assertEquals(posted,BackupCodec.decode(BackupCodec.encode(posted)))
    }
    @Test fun intradayModelOrWrongDatePriceDoesNotChangeShares() {
        val p = Portfolio(holdings=listOf(h(daily())))
        val result = advance(p,points=listOf(DailyNav("000001","2026-11-23",d("2"),final=false)))
        assertEquals(p.holdings.single().shares,result.holdings.single().shares)
        assertTrue(result.trades.isEmpty())
    }
    @Test fun laterOrSameDateSellAndCalibrationRequireReviewInsteadOfCostRewrite() {
        for (kind in listOf("SELL","CALIBRATE")) {
            val base = h(daily())
            val t = Trade("manual",base.id,kind,d("5"),d("1"),BigDecimal.ZERO,"2026-11-23")
            val p = Portfolio(holdings=listOf(base),trades=listOf(t))
            val result = advance(p)
            assertEquals(p.trades,result.trades)
            assertEquals(DcaStatus.REVIEW,result.executions.first().status)
        }
        val base=h(daily())
        val p=Portfolio(holdings=listOf(base),trades=listOf(Trade("manual",base.id,"BUY",d("5"),d("1"),BigDecimal.ZERO,"2026-11-24")))
        assertTrue(advance(p).executions.any { it.status==DcaStatus.REVIEW })
    }
    @Test fun pauseResumeExcludesPausedDaysAndKeepsPriorPendingEntries() {
        val base = advance(Portfolio(holdings=listOf(h(daily()))),"2026-11-23",emptyList())
        val paused = DcaEngine.setPaused(base,"h",true,"2026-11-23")
        val resumed = DcaEngine.setPaused(paused,"h",false,"2026-11-25")
        val result = advance(resumed,"2026-11-25",prices("2026-11-23","2026-11-25"))
        assertEquals(listOf("2026-11-23","2026-11-25"),result.trades.map { it.date })
    }
    @Test fun changingPlanCancelsOnlyUnpostedEntriesAndAvoidsSecondPurchaseToday() {
        val posted = advance(Portfolio(holdings=listOf(h(daily()))),"2026-11-23",prices("2026-11-23"))
        val changed = DcaEngine.replacePlan(posted,"h",daily().copy(revision="v2"),d("20"),"2026-11-23")
        assertEquals("2026-11-24",changed.holdings.single().dcaSchedule!!.startDate)
        assertEquals(1,advance(changed,"2026-11-23",prices("2026-11-23")).trades.size)
        val waiting = advance(Portfolio(holdings=listOf(h(daily()))),"2026-11-23",emptyList())
        val replaced = DcaEngine.replacePlan(waiting,"h",daily().copy(revision="v2"),d("20"),"2026-11-23")
        assertEquals(DcaStatus.CANCELLED,replaced.executions.single().status)
    }
    @Test fun currencyMismatchAndTinyAmountNeverPostIncorrectTrades() {
        val p=Portfolio(holdings=listOf(h(daily().copy(currency="USD"))))
        assertTrue(advance(p).trades.isEmpty())
        val tiny=Portfolio(holdings=listOf(h(daily()).copy(dcaAmount=d("0.000000001"))))
        assertTrue(advance(tiny).trades.isEmpty())
    }
    @Test fun legacyTradesStayUntouchedAndUnpostedLegacyPlansUseRealDatedPrices() {
        val base=h(daily()).copy(dcaSchedule=null,dcaLastDate="2026-11-20")
        val p=Portfolio(holdings=listOf(base))
        val result=advance(p)
        assertEquals(2,result.trades.size)
        assertTrue(DcaEngine.history(result).all { it.status==DcaStatus.RECORDED })
        val legacy=Trade("old",base.id,"DCA",d("10"),d("1"),BigDecimal.ZERO,"2026-11-20")
        assertTrue(DcaEngine.history(p.copy(trades=listOf(legacy))).single().note.contains("旧版"))
    }
    @Test fun skippedOccurrenceDoesNotReappearAndFollowingEntryCanProceed() {
        val waiting=advance(Portfolio(holdings=listOf(h(daily()))),points=emptyList())
        val skipped=DcaEngine.skip(waiting,waiting.executions.first().id)
        val result=advance(skipped)
        assertEquals(1,result.trades.size)
        assertEquals("2026-11-24",result.trades.single().date)
        assertEquals(DcaStatus.SKIPPED,result.executions.first().status)
    }
    @Test fun newlyVerifiedClosedDayDoesNotBlockTheNextTradingDay() {
        val base=h(daily("2026-11-23"))
        val unknown=TradingCalendar(emptySet(),emptySet())
        val waiting=DcaEngine.advance(Portfolio(holdings=listOf(base)),"2026-11-24",emptyList(),unknown).portfolio
        val verified=TradingCalendar(setOf(2026),setOf("2026-11-23"))
        val result=DcaEngine.advance(waiting,"2026-11-24",prices("2026-11-24"),verified).portfolio
        assertEquals(DcaStatus.SKIPPED,result.executions.first().status)
        assertEquals("2026-11-24",result.trades.single().date)
    }

    @Test fun legacyPlanWithoutInitialSnapshotUsesThePersistedCursor() {
        val legacy=h(daily()).copy(confirmedDate=null,initialShares=null,dcaSchedule=null,dcaLastDate="2026-11-20")
        val result=advance(Portfolio(holdings=listOf(legacy)))
        assertEquals(listOf("2026-11-23","2026-11-24"),result.trades.map { it.date })
    }

}
