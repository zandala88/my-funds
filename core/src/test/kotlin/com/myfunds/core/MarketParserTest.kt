package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MarketParserTest {
    @Test fun publishedNavSurvivesAbsentEstimate() {
        val row = MarketParser.quotes("""{"ErrCode":0,"Datas":[{"FCODE":"000001","SHORTNAME":"华夏成长","PDATE":"2026-11-23","NAV":"0.9450","NAVCHGRT":"0.85","GSZ":null,"GSZZL":null,"GZTIME":null}]}""").single()
        assertEquals("000001", row.code)
        assertEquals(BigDecimal("0.9450"), row.nav)
        assertNull(row.estimate)
        assertNull(row.estimateChange)
        assertNull(row.estimateTime)
    }

    @Test fun zeroEstimateChangeIsKnownButPlaceholderIsUnknown() {
        val data = """{"ErrCode":0,"Datas":[{"FCODE":"000001","NAV":"1","PDATE":"2026-11-24","GSZ":"1","GSZZL":"0.00","GZTIME":"2026-11-24 14:00"},{"FCODE":"000002","NAV":"--","GSZ":"--","GSZZL":"--"}]}"""
        val rows = MarketParser.quotes(data)
        assertEquals(BigDecimal("0.00"), rows.first().estimateChange)
        assertNull(rows.last().nav)
    }

    @Test fun businessFailureAndNullDataAreErrors() {
        assertThrows(IllegalArgumentException::class.java) { MarketParser.quotes("""{"Success":false,"ErrCode":611,"ErrMsg":"网络繁忙","Datas":null}""") }
        assertThrows(IllegalArgumentException::class.java) { MarketParser.quotes("""{"ErrCode":0,"Datas":null}""") }
        assertThrows(IllegalArgumentException::class.java) { MarketParser.quotes("<html>error</html>") }
    }

    @Test fun searchPreservesCodeAndFiltersNonFundResults() {
        val results = MarketParser.search("""{"ErrCode":0,"Datas":[{"CODE":"000001","NAME":"华夏成长"},{"CODE":"not-fund","NAME":"其他"}]}""")
        assertEquals(listOf(SearchResult("000001", "华夏成长")), results)
    }

    @Test fun historyFiltersMissingValuesAndRetainsDates() {
        val points = MarketParser.history("""{"ErrCode":0,"Datas":[{"FSRQ":"2026-11-23","DWJZ":"0.9450"},{"FSRQ":"2026-11-24","DWJZ":"--"}]}""")
        assertEquals(listOf(ChartPoint("2026-11-23", BigDecimal("0.9450"))), points)
    }

    @Test fun indicesTreatDashAsUnknownAndBusinessFailureAsError() {
        val rows = MarketParser.indices("""{"rc":0,"data":{"diff":[{"f12":"000001","f14":"上证指数","f2":3545.83,"f3":0.19},{"f12":"399001","f14":"深证成指","f2":"-","f3":"-"}]}}""")
        assertEquals(BigDecimal("3545.83"), rows.first().price)
        assertNull(rows.last().price)
        assertThrows(IllegalArgumentException::class.java) { MarketParser.indices("""{"rc":1,"data":null}""") }
    }

    @Test fun marketDatesMustBeValidAndIndexTimeIsRetained() {
        val quote = MarketParser.quotes("""{"ErrCode":0,"Datas":[{"FCODE":"000001","NAV":"1","PDATE":"2026-99-99"}]}""").single()
        assertNull(quote.date)
        val index = MarketParser.indices("""{"rc":0,"data":{"diff":[{"f12":"000001","f13":1,"f14":"上证指数","f2":"1","quoteTime":"2026-11-24 15:00:00","f62":"1234"}]}}""").single()
        assertEquals("2026-11-24 15:00:00", index.time)
        assertEquals(BigDecimal("1234"), index.netFlow)
    }
}
