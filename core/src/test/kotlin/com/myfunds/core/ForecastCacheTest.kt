package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class ForecastCacheTest {
    private val model = FundEstimate("2026-11-25", BigDecimal.ONE, BigDecimal.ONE, "2026-11-24", "2026-11-25 14:00", EstimateMethod.INDEX,
        BigDecimal.ZERO, BigDecimal("100"), true, "2026-09-30", "代理")
    private val old = FundQuote("000001", "测试美元", BigDecimal.ONE, "2026-11-24", kind = FundKind.QDII, model = model.copy(currency = "USD"), currency = "USD")
    @Test fun fastFormalRefreshKeepsSameBaselineModelAndCurrencyUntilEnrichmentCompletes() {
        val fresh = FundQuote("000001", "测试美元", BigDecimal.ONE, "2026-11-24")
        val merged = MarketCache.mergeQuotes(mapOf(old.code to old), listOf(fresh)).getValue(old.code)
        assertEquals(old.model, merged.model)
        assertEquals("USD", merged.currency)
        assertEquals(FundKind.QDII, merged.kind)
    }
    @Test fun explicitFailedReestimationClearsObsoleteModel() {
        val fresh = old.copy(model = null, estimateReason = "缺少行情")
        val merged = MarketCache.mergeQuotes(mapOf(old.code to old), listOf(fresh)).getValue(old.code)
        assertNull(merged.model)
        assertEquals("缺少行情", merged.estimateReason)
    }
    @Test fun advancingPublishedDateDoesNotRetainOldBaselineModel() {
        val fresh = old.copy(date = "2026-11-25", nav = BigDecimal("1.02"), change = BigDecimal("2"), model = null)
        val merged = MarketCache.mergeQuotes(mapOf(old.code to old), listOf(fresh)).getValue(old.code)
        assertNull(merged.model)
    }
    @Test fun listedMarketEstimateCanBeCachedWithoutOfficialNav() {
        val market = model.copy(method = EstimateMethod.MARKET, basisDate = null)
        val fresh = FundQuote("510300", "ETF", null, null, kind = FundKind.ETF, model = market)
        val merged = MarketCache.mergeQuotes(emptyMap(), listOf(fresh))
        assertEquals(market, merged[fresh.code]?.model)
        assertEquals(fresh, MarketParser.quotes(MarketParser.encodeQuotes(merged.values.toList())).single())
    }

    @Test fun explicitlyInvalidProviderIsNotResurrectedFromTheOldQuote() {
        val before = old.copy(estimate = BigDecimal("1.02"), estimateTime = "2026-11-25 14:00", estimateVerified = true)
        val incoming = before.copy(estimate = null, estimateTime = null, estimateVerified = false)
        val merged = MarketCache.mergeQuotes(mapOf(before.code to before), listOf(incoming)).getValue(before.code)
        assertNull(merged.estimate)
        assertNull(merged.estimateTime)
        assertEquals(before.nav, merged.nav)
        assertEquals(false, merged.estimateVerified)
    }

}
