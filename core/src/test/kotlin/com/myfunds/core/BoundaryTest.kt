package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class BoundaryTest {
    @Test fun inputStreamLimitWorksWithoutNewAndroidApis() {
        assertArrayEquals(byteArrayOf(1, 2, 3), readBounded(byteArrayOf(1, 2, 3).inputStream(), 3))
        assertThrows(IllegalArgumentException::class.java) { readBounded(ByteArray(4).inputStream(), 3) }
    }
    @Test fun fractionalSaleRoundsRealizedMoneyWithoutLosingSharePrecision() {
        val p = Portfolio(holdings = listOf(Holding("h", "000001", "测试", shares = BigDecimal("100.12345678"), cost = BigDecimal("1.3050000000000000"))))
        val result = PortfolioOperations.sell(p, "h", BigDecimal("0.12345678"), BigDecimal("1.45678901"), BigDecimal("0.01"), "2026-11-24")
        assertEquals(BigDecimal("100.00000000"), result.holdings.single().shares)
        assertEquals(BigDecimal("0.01"), result.trades.single().realized)
    }
}
