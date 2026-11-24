package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class ReviewRegressionTest {
    private val zero = BigDecimal.ZERO
    private fun d(s: String) = BigDecimal(s)
    private val quote = FundQuote("000001", "测试", d("1.10"), "2026-11-24", change = d("10"))
    private fun emptyHolding() = Holding("h", "000001", "测试", confirmedDate = "2026-11-24", initialShares = zero)

    @Test fun sameDayBuyDoesNotEarnPriorRiseAndFullSaleRetainsDayProfit() {
        val bought = PortfolioOperations.buy(Portfolio(holdings = listOf(emptyHolding())), "h", d("100"), d("1.10"), zero, "2026-11-24")
        assertEquals(0, MarketMath.portfolioDaily(bought, bought.holdings.single(), quote, "2026-11-24")!!.amount.compareTo(zero))
        val old = emptyHolding().copy(shares = d("100"), cost = d("1"), confirmedDate = "2026-11-23", initialShares = d("100"))
        val sold = PortfolioOperations.sell(Portfolio(holdings = listOf(old)), "h", d("100"), d("1.10"), zero, "2026-11-24")
        assertEquals(0, MarketMath.portfolioDaily(sold, sold.holdings.single(), quote, "2026-11-24")!!.amount.compareTo(d("10")))
        assertEquals(d("10.00"), MarketMath.portfolioDailies(sold, mapOf("000001" to quote), "2026-11-24").single()!!.amount)
    }
    @Test fun unknownSnapshotAndSameDayCalibrationMakeDailyUnknown() {
        val h = emptyHolding().copy(shares = d("100"), initialShares = d("100"))
        assertNull(MarketMath.portfolioDaily(Portfolio(holdings = listOf(h)), h, quote, "2026-11-24"))
        val p = PortfolioOperations.calibrate(Portfolio(holdings = listOf(h)), "h", d("110"), d("1"), "2026-11-24")
        assertNull(MarketMath.portfolioDaily(p, p.holdings.single(), quote, "2026-11-24"))
    }
    @Test fun rejectsBackdatedOperationsAndImpossibleLedgerProfit() {
        val h = emptyHolding().copy(confirmedDate = "2026-11-01")
        val bought = PortfolioOperations.buy(Portfolio(holdings = listOf(h)), "h", d("100"), d("3"), zero, "2026-11-10")
        assertThrows(IllegalArgumentException::class.java) { PortfolioOperations.sell(bought, "h", d("10"), d("2"), zero, "2026-11-01") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(bought.copy(trades = listOf(bought.trades.single().copy(realized = d("1000"))))) }
        val calibrated = PortfolioOperations.calibrate(bought, "h", d("100"), d("1"), "2026-11-24")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(calibrated.copy(trades = calibrated.trades.map { if (it.kind == "CALIBRATE") it.copy(fee = d("1")) else it })) }
        assertThrows(IllegalArgumentException::class.java) { PortfolioOperations.buy(bought, "h", d("1"), d("1"), zero, "9999-01-01") }
    }
    @Test fun strictIntegerAndUtf8RejectCorruptionBeforeReplacement() {
        val raw = BackupCodec.encode(Portfolio(holdings = listOf(emptyHolding())))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(raw.replace("\"order\":0", "\"order\":1.9")) }
        assertThrows(IllegalArgumentException::class.java) { decodeUtf8Strict(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertEquals("中文", decodeUtf8Strict("中文".toByteArray()))
        assertEquals(Portfolio(holdings = listOf(emptyHolding())), BackupCodec.prepare(raw).portfolio)
    }
    @Test fun largeUnicodePayloadCanBeReadThroughSmallSqliteWindows() {
        val original = "数据🌿".repeat(300000).toByteArray()
        var requests = 0
        val result = readChunked(original.size) { offset, length ->
            assertTrue(length <= 256 * 1024); requests++
            original.copyOfRange(offset, offset + length)
        }
        assertArrayEquals(original, result); assertTrue(requests > 2)
    }
    @Test fun partialMarketResponseRetainsValidFieldsAndMissingSecurities() {
        val old = quote.copy(estimate = d("1.09"), estimateChange = d("9"), estimateTime = "2026-11-24 14:00")
        val incoming = quote.copy(change = null)
        val merged = MarketCache.mergeQuotes(mapOf("000001" to old), listOf(incoming)).getValue("000001")
        assertEquals(old.change, merged.change); assertEquals(old.estimate, merged.estimate)
        val indices = listOf(IndexQuote("1.000001", "上证", d("1"), zero), IndexQuote("0.399001", "深证", d("2"), zero))
        assertEquals(indices, MarketCache.mergeIndices(indices, emptyList()))
        assertEquals(2, MarketCache.mergeIndices(indices, listOf(indices.first().copy(price = d("3")))).size)
    }
    @Test fun latestSelectionWinsAndDisplayControlsRemainIndependent() {
        val gate = LatestRequest(); val month = gate.next(); val year = gate.next()
        assertFalse(gate.isCurrent(month)); assertTrue(gate.isCurrent(year))
        assertEquals(listOf("平均成本"), PortfolioMath.metadata(emptyHolding(), AppSettings(showShares = false, showCost = true)).map { it.first })
        assertEquals(d("5"), PortfolioSummary(d("100"), d("3"), d("2"), 0).totalProfit)
        val h1 = emptyHolding(); val h2 = h1.copy(id = "h2", code = "000002")
        val q1 = quote.copy(change = d("1"), estimateChange = d("99"), estimateTime = "2026-11-23 14:00")
        val q2 = quote.copy(code = "000002", change = d("2"))
        val p = Portfolio(holdings = listOf(h1, h2), settings = AppSettings(sort = "change"))
        assertEquals("000002", PortfolioMath.sorted(p, mapOf("000001" to q1, "000002" to q2), "2026-11-24").first().code)
    }
    @Test fun holdingReturnRateRequiresKnownPositiveCostAndShares() {
        val h = emptyHolding().copy(shares = d("100"), cost = d("1.25"))
        assertEquals(0, d("20").compareTo(PortfolioMath.holdingRate(h, d("1.50"))!!))
        assertNull(PortfolioMath.holdingRate(h.copy(cost = zero), d("1.50")))
        assertNull(PortfolioMath.holdingRate(h.copy(shares = zero), d("1.50")))
        assertNull(PortfolioMath.holdingRate(h, null))
    }
}
