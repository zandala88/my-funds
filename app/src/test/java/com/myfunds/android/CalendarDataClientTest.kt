package com.myfunds.android

import com.myfunds.core.*
import java.time.Instant
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class CalendarDataClientTest {
    private val cache=mutableMapOf<String,String>()
    private fun client(fetch: (String)->String)=CalendarDataClient(fetch,cache::get,{ k,v->cache[k]=v },{ Instant.parse("2026-11-25T02:00:00Z") })
    @Test fun monthlyNavUsesDatedEndpointAndFallsBackToCachedHistoryOnFailure() {
        var url=""
        val first=client { u-> url=u; """{"TotalCount":2,"Data":{"FundType":"002","LSJZList":[{"FSRQ":"2026-11-24","DWJZ":"1.02","JZZZL":"2"},{"FSRQ":"2026-11-23","DWJZ":"1.00"}]}}""" }
            .history(FundQuote("000001","基金",null,null),YearMonth.of(2026,11))
        assertTrue(url.contains("f10/lsjz")); assertTrue(url.contains("startDate=2026-10-22")); assertTrue(url.contains("pageSize=500"))
        assertTrue(first.complete); assertEquals("2026-11-23",first.points.last().referenceDate)
        assertEquals(first,client { throw IllegalStateException("offline") }.history(FundQuote("000001","基金",null,null),YearMonth.of(2026,11)))
    }
    @Test fun listedHistoryUsesUnadjustedCloseAndNeverCallsItFinalIntraday() {
        var url=""
        val result=client { u->url=u; """{"code":0,"data":{"sh510300":{"day":[["2026-11-25","4","4.1","4.2","3.9","5"]]}}}""" }
            .history(FundQuote("510300","沪深300ETF",null,null,kind=FundKind.ETF),YearMonth.of(2026,11))
        assertFalse(url.contains("qfq")); assertFalse(result.points.single().final)
    }
    @Test fun badAnnualCalendarDoesNotEraseVerifiedYears() {
        val builtin=TradingCalendar.builtin()
        val result=client { "2027年休市安排 元旦：1月1日休市" }.calendar(builtin)
        assertEquals(builtin,result); assertEquals(TradingDay.UNKNOWN,result.status("2027-01-04"))
    }
    @Test fun cachedIntradayCloseDoesNotBecomeFinalWhenTheClockAdvancesOffline() {
        var clock=Instant.parse("2026-11-25T02:00:00Z"); var offline=false
        val client=CalendarDataClient({ if(offline) throw IllegalStateException("offline") else """{"code":0,"data":{"sh510300":{"day":[["2026-11-25","4","4.1","4.2","3.9","5"]]}}}""" },cache::get,{ k,v->cache[k]=v },{clock})
        val q=FundQuote("510300","沪深300ETF",null,null,kind=FundKind.ETF)
        assertFalse(client.history(q,YearMonth.of(2026,11)).points.single().final)
        clock=Instant.parse("2026-11-25T08:00:00Z"); offline=true
        assertFalse(client.history(q,YearMonth.of(2026,11)).points.single().final)
    }

}
