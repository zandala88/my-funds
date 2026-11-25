package com.myfunds.android

import com.myfunds.core.*
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EstimationClientTest {
    private val day = "2026-11-25"
    private val clock = { Instant.parse("2026-11-25T06:30:00Z") }
    private fun quote(code: String = "161725") = FundQuote(code, "白酒指数", BigDecimal("1.00"), "2026-11-24")
    private fun wire(symbol: String = "sz399997"): String {
        val f = MutableList(40) { "" }; f[1] = "白酒"; f[3] = "102"; f[4] = "100"; f[30] = "20261125142000"
        return "v_$symbol=\"${f.joinToString("~")}\";"
    }
    private fun response(url: String): String {
        val code = URI(url).rawQuery?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == "FCODE" }?.getOrNull(1) ?: "161725"
        return when {
            url.contains("FundMNNBasicInformation") -> """{"Datas":{"FCODE":"$code","SHORTNAME":"白酒指数","FTYPE":"指数型-股票","INDEXCODE":"399997","NEWINDEXTEXCH":"0"}}"""
            url.contains("FundMNAssetAllocationNew") -> """{"Datas":[{"FSRQ":"2026-09-30","GP":"95","HB":"5"}]}"""
            url.contains("FundMNInverstPosition") -> """{"Datas":{"fundStocks":[],"fundfofs":[]},"Expansion":"2026-09-30"}"""
            url.contains("qt.gtimg.cn") -> wire()
            url.contains("fundgz.") -> "<html>页面不存在</html>"
            else -> throw IllegalArgumentException("Unexpected request: $url")
        }
    }
    @Test fun brokenEstimateSourceFallsBackToLiveModelAndPreservesFormalNav() {
        val rows = EstimationClient(::response, now = clock).enrich(listOf(quote()), day)
        val q = rows.single()
        assertEquals(BigDecimal("1.00"), q.nav)
        assertEquals("2026-11-24", q.date)
        assertNull(q.estimate)
        assertEquals(0, BigDecimal("1.9").compareTo(q.model!!.change))
        assertEquals(day, q.model!!.date)
        assertTrue(q.model!!.complete)
    }
    @Test fun metadataCacheAvoidsRepeatedRequestsButQuotesStayFresh() {
        val cache = ConcurrentHashMap<String, String>()
        val calls = CopyOnWriteArrayList<String>()
        val client = EstimationClient({ u -> calls.add(u); response(u) }, cache::get, { k, v -> cache[k] = v }, clock)
        assertNotNull(client.enrich(listOf(quote()), day).single().model)
        calls.clear()
        assertNotNull(client.enrich(listOf(quote()), day).single().model)
        assertFalse(calls.any { it.contains("FundMNNBasicInformation") || it.contains("FundMNAssetAllocationNew") || it.contains("FundMNInverstPosition") })
        assertTrue(calls.any { it.contains("qt.gtimg.cn") })
        val tomorrow = EstimationClient({ u -> calls.add(u); response(u) }, cache::get, { k, v -> cache[k] = v }, { Instant.parse("2026-11-26T08:00:00Z") })
        calls.clear(); tomorrow.enrich(listOf(quote()), "2026-11-26")
        assertTrue(calls.any { it.contains("FundMNNBasicInformation") })
    }
    @Test fun oneFundAndCacheFailuresCannotDiscardOtherFormalQuotes() {
        val rows = EstimationClient({ u ->
            if (u.contains("FCODE=000001")) throw IllegalStateException("资料服务失败")
            response(u)
        }, { throw IllegalStateException("缓存损坏") }, { _, _ -> throw IllegalStateException("无法写缓存") }, clock)
            .enrich(listOf(quote(), quote("000001").copy(name = "未知基金")), day)
        assertEquals(2, rows.size)
        assertNotNull(rows.first().model)
        assertEquals(BigDecimal("1.00"), rows.last().nav)
        assertNotNull(rows.last().estimateReason)
    }
    @Test fun sharedSecurityRequestsAreDeduplicatedAndFormalTodayNeedsNoModel() {
        val batches = AtomicInteger()
        val client = EstimationClient({ u -> if (u.contains("qt.gtimg.cn")) batches.incrementAndGet(); response(u) }, now = clock)
        val rows = client.enrich(listOf(quote(), quote("000002")), day)
        assertTrue(rows.all { it.model != null })
        assertEquals(1, batches.get())
        val official = quote().copy(date = day, change = BigDecimal("2"))
        val current = client.enrich(listOf(official), day).single()
        assertEquals(official.nav, current.nav)
        assertNull(current.model)
    }
    @Test fun cancellationStopsFurtherAcquisitionInsteadOfCreatingMissingData() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            EstimationClient(::response, now = clock, checkCancelled = { throw java.util.concurrent.CancellationException() }).enrich(listOf(quote()), day)
        }
    }
    @Test fun validProviderEstimateIsKeptAheadOfTheModel() {
        val provider = """jsonpgz({"fundcode":"161725","name":"白酒指数","jzrq":"2026-11-24","dwjz":"1.00","gsz":"1.03","gszzl":"3","gztime":"2026-11-25 14:20"});"""
        val client = EstimationClient({ u -> if (u.contains("fundgz.")) provider else response(u) }, now = clock)
        val q = client.enrich(listOf(quote()), day).single()
        assertEquals(BigDecimal("1.03"), q.estimate)
        assertNull(q.model)
    }
    @Test fun explicitExchangeCalendarSuppliesDomesticReturnReferenceDate() {
        val calendar = """{"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Shanghai","currency":"CNY"},"timestamp":[1795392000,1795478400,1795564800],"indicators":{"quote":[{"close":[98,100,102]}]}}]}}"""
        val q = EstimationClient({ u -> if (u.contains("000300.SS")) calendar else response(u) }, now = clock).enrich(listOf(quote()), day).single()
        assertEquals("2026-11-24", q.model!!.referenceDate)
    }

    @Test fun metadataOutageNeverReclassifiesUsdIntoRmb() {
        val q = quote().copy(name = "海外美元基金", currency = "USD")
        val rows = EstimationClient({ throw IllegalStateException("资料不可用") }, now = clock).enrich(listOf(q), day)
        assertEquals("USD", rows.single().currency)
    }
    @Test fun emptySuccessfulMetadataUsesShortRetryInsteadOfFullDayCache() {
        val cache = ConcurrentHashMap<String, String>()
        val calls = CopyOnWriteArrayList<String>()
        val transport: (String) -> String = { u ->
            calls.add(u)
            if (u.contains("FundMNAssetAllocationNew") || u.contains("FundMNInverstPosition")) "{\"Datas\":null}" else response(u)
        }
        EstimationClient(transport, cache::get, { k, v -> cache[k] = v }, clock).enrich(listOf(quote()), day)
        calls.clear()
        EstimationClient(transport, cache::get, { k, v -> cache[k] = v }, { Instant.parse("2026-11-25T06:40:00Z") }).enrich(listOf(quote()), day)
        assertTrue("Missing metadata suppressed retries for 24 hours", calls.any { it.contains("FundMNNBasicInformation") })
    }
    @Test fun listedEtfGetsMarketDataEvenAfterSameDayNavIsPublished() {
        val q = quote("510300").copy(date = day, change = BigDecimal("5"))
        val client = EstimationClient({ u -> when {
            u.contains("FundMNNBasicInformation") -> """{"Datas":{"FCODE":"510300","SHORTNAME":"沪深300ETF","FTYPE":"指数型-股票","ISLIST":"1","LISTTEXCHMARK":"SH"}}"""
            u.contains("qt.gtimg.cn") -> wire("sh510300")
            else -> response(u)
        } }, now = clock)
        assertEquals(EstimateMethod.MARKET, client.enrich(listOf(q), day).single().model?.method)
    }
    @Test fun estimateClockInTheFutureIsDiscardedBeforeFallback() {
        val q = quote().copy(estimate = BigDecimal("1.05"), estimateTime = "2026-11-25 23:00")
        val updated = EstimationClient(::response, now = clock).enrich(listOf(q), day).single()
        assertNull(updated.estimate)
        assertNotNull(updated.model)
    }

    @Test fun fofPublishedChildGetsReferenceDateFromValidatedNavHistory() {
        val q = quote("006580").copy(name = "FOF")
        val transport: (String) -> String = { u -> when {
            u.contains("FundMNFInfo") -> """{"Datas":[{"FCODE":"000002","SHORTNAME":"子基金","NAV":"1.02","PDATE":"2026-11-25","NAVCHGRT":"2"}]}"""
            u.contains("FundMNNBasicInformation") && u.contains("FCODE=006580") -> """{"Datas":{"FCODE":"006580","SHORTNAME":"FOF","FTYPE":"FOF-稳健"}}"""
            u.contains("FundMNAssetAllocationNew") && u.contains("FCODE=006580") -> """{"Datas":[{"FSRQ":"2026-09-30","JJ":"100"}]}"""
            u.contains("FundMNInverstPosition") && u.contains("FCODE=006580") -> """{"Expansion":"2026-09-30","Datas":{"fundfofs":[{"TZJJDM":"000002","ZJZBL":"100"}]}}"""
            u.contains("FundNetDiagram") -> """{"Datas":[{"FSRQ":"2026-11-24","DWJZ":"1"},{"FSRQ":"2026-11-25","DWJZ":"1.02"}]}"""
            else -> response(u)
        } }
        val e = EstimationClient(transport, now = clock).enrich(listOf(q), day).single().model
        assertNotNull("A valid published child interval was not resolved", e)
        assertEquals(0, BigDecimal("2").compareTo(e!!.change))
        assertTrue(e.complete)
    }

}
