package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class NavHistoryTest {
    @Test fun realDailyHistoryPreservesPreviousNavAndDates() {
        val raw = """{"ErrCode":0,"TotalCount":2,"Data":{"FundType":"002","LSJZList":[{"FSRQ":"2026-11-24","DWJZ":"1.02","JZZZL":"2","FHFCZ":""},{"FSRQ":"2026-11-23","DWJZ":"1","JZZZL":"0"}]}}"""
        val result = NavHistoryParser.parse(raw,"000001","2026-11-01","2026-11-25")
        assertTrue(result.complete)
        assertEquals(BigDecimal.ONE, result.points.last().previousNav)
        assertEquals("2026-11-23", result.points.last().referenceDate)
    }
    @Test fun moneyWireValueIsNotUsedAsPriceEvenWithoutCachedFundMetadata() {
        val result = NavHistoryParser.parse("""{"TotalCount":1,"Data":{"FundType":"005","SYType":"每万份收益","LSJZList":[{"FSRQ":"2026-11-24","DWJZ":"0.2185","JZZZL":"0.00"}]}}""", "000198","2026-11-01","2026-11-25")
        assertEquals(BigDecimal.ONE, result.points.single().nav)
        assertEquals(BigDecimal("0.2185"), result.points.single().incomePerTenThousand)
    }
    @Test fun incompletePaginationAndInvalidRowsDoNotProveMissingNav() {
        val result = NavHistoryParser.parse("""{"TotalCount":30,"Data":{"LSJZList":[{"FSRQ":"2026-11-24","DWJZ":"--"},{"FSRQ":"2026-99-99","DWJZ":"1"}]}}""", "000001","2026-11-01","2026-11-25")
        assertFalse(result.complete)
        assertTrue(result.points.isEmpty())
        assertTrue("2026-11-24" in result.invalidDates)
        assertThrows(IllegalArgumentException::class.java) { NavHistoryParser.parse("{\"ErrCode\":1}","000001","2026-11-01","2026-11-25") }
    }
    @Test fun marketHistoryUsesUnadjustedPricesAndNeverTreatsIntradayBarAsFinal() {
        val raw = """{"code":0,"data":{"sh510300":{"day":[["2026-11-23","100","100"],["2026-11-24","101","102"]]}}}"""
        val result = NavHistoryParser.market(raw,"510300","sh510300","2026-11-01","2026-11-24","2026-11-24T14:00:00")
        assertEquals(BigDecimal("102"), result.points.last().nav)
        assertFalse(result.points.last().final)
        assertTrue(result.points.first().final)
        assertThrows(IllegalArgumentException::class.java) { NavHistoryParser.market(raw.replace("\"day\"","\"qfqday\""),"510300","sh510300","2026-11-01","2026-11-24","2026-11-24T16:00:00") }
    }
}
