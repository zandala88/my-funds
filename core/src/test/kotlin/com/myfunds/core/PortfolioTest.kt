package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class PortfolioTest {
    private fun d(s: String) = BigDecimal(s)
    private val holding = Holding("h1", "000001", "华夏成长", "default", d("100"), d("1.20"))
    private fun portfolio() = Portfolio(holdings = listOf(holding))

    @Test fun buyIncludesFeesAndSellKeepsAverageCost() {
        val bought = PortfolioOperations.buy(portfolio(), "h1", d("100"), d("1.40"), d("1.00"), "2026-11-24")
        assertEquals(0, d("1.305").compareTo(bought.holdings.single().cost))
        val sold = PortfolioOperations.sell(bought, "h1", d("50"), d("1.50"), d("0.50"), "2026-11-24")
        assertEquals(0, d("150").compareTo(sold.holdings.single().shares))
        assertEquals(0, d("1.305").compareTo(sold.holdings.single().cost))
        assertEquals(0, d("9.25").compareTo(sold.trades.last().realized))
    }

    @Test fun rejectsOversellNegativeAndInvalidFeeWithoutMutation() {
        for (action in listOf<() -> Unit>(
            { PortfolioOperations.sell(portfolio(), "h1", d("101"), d("1"), d("0"), "2026-11-24") },
            { PortfolioOperations.buy(portfolio(), "h1", d("-1"), d("1"), d("0"), "2026-11-24") },
            { PortfolioOperations.sell(portfolio(), "h1", d("1"), d("1"), d("2"), "2026-11-24") }
        )) assertThrows(IllegalArgumentException::class.java, action)
        assertEquals(d("100"), holding.shares)
    }

    @Test fun backupRestoresAllUserDataAndLeadingZeroCode() {
        val p = PortfolioOperations.buy(portfolio(), "h1", d("0.12345678"), d("1.23456789"), d("0.01"), "2026-11-24")
            .copy(settings = AppSettings(darkMode = true, autoRefresh = true, sort = "name"))
        val json = BackupCodec.encode(p)
        assertEquals(p, BackupCodec.decode(json))
        assertTrue(json.contains("000001"))
        assertEquals(p, BackupCodec.decode(BackupCodec.encode(BackupCodec.decode(json))))
    }

    @Test fun rejectsFutureCorruptAndDuplicateBackup() {
        val json = BackupCodec.encode(portfolio())
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("\"schemaVersion\":2", "\"schemaVersion\":999")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("broken") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(portfolio().copy(holdings = listOf(holding, holding))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(portfolio().copy(holdings = listOf(holding.copy(groupId = "missing")))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(portfolio().copy(holdings = listOf(holding.copy(shares = d("-1"))))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("\"1.20\"", "\"NaN\"")) }
    }

    @Test fun summaryDistinguishesUnknownQuoteFromZero() {
        val empty = PortfolioMath.summary(portfolio(), emptyMap())
        assertNull(empty.marketValue)
        assertEquals(1, empty.missing)
        val known = PortfolioMath.summary(portfolio(), mapOf("000001" to d("1.20")))
        assertEquals(0, known.profit!!.compareTo(BigDecimal.ZERO))
    }

    @Test fun calibrationIsRecordedWithoutInventingHistoricalProfit() {
        val corrected = PortfolioOperations.calibrate(portfolio(), "h1", d("125.25"), d("1.23"), "2026-11-24")
        assertEquals(d("125.25"), corrected.holdings.single().shares)
        assertEquals("CALIBRATE", corrected.trades.last().kind)
        assertEquals(0, corrected.trades.last().realized.compareTo(BigDecimal.ZERO))
        assertThrows(IllegalArgumentException::class.java) { PortfolioOperations.buy(portfolio(), "h1", d("1"), d("1"), d("0"), "2026-02-30") }
    }

    @Test fun rejectsDuplicateIdentityAndInvalidLedgerReferences() {
        val p = PortfolioOperations.buy(portfolio(), "h1", d("1"), d("1"), d("0"), "2026-11-24")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(p.copy(trades = p.trades + p.trades)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(p.copy(trades = listOf(p.trades.single().copy(holdingId = "missing")))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("x".repeat(BackupCodec.MAX_BYTES + 1)) }
    }

    @Test fun dcaCatchUpAccumulatesSharesAndRecalculatesAverageCost() {
        val baseHolding = Holding("h1", "000001", "华夏成长", "default", d("100"), d("1.00"), confirmedDate = "2026-11-20",
            initialShares = d("100"), dcaShares = d("50"), dcaLastDate = "2026-11-20", dcaWeekdaysOnly = true)
        val p = Portfolio(holdings = listOf(baseHolding))
        // 2026-11-20 is Friday. Intermediate dates: 21 (Sat, skip), 22 (Sun, skip), 23 (Mon, execute), 24 (Tue, execute).
        val quotes = mapOf("000001" to FundQuote("000001", "华夏成长", d("2.00"), "2026-11-24"))
        val updated = PortfolioOperations.applyDcaCatchUp(p, "2026-11-24", quotes, listOf(DailyNav("000001","2026-11-23",d("2.00"))))
        // Two trades should be executed:
        assertEquals(2, updated.trades.size)
        assertTrue(updated.trades.all { it.kind == "DCA" })
        // Shares: 100 + 50 + 50 = 200
        assertEquals(d("200"), updated.holdings.single().shares)
        // dcaLastDate updated to today:
        assertEquals("2026-11-24", updated.holdings.single().dcaLastDate)
        // Check backup encoding/decoding preserves DCA configuration
        val encoded = BackupCodec.encode(updated)
        val decoded = BackupCodec.decode(encoded)
        assertEquals(updated, decoded)
    }
}
