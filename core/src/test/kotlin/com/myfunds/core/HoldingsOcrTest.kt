package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class HoldingsOcrTest {
    private fun d(s: String) = BigDecimal(s)
    @Test fun alipayListAmountsAreNotSharesOrUnitCost() {
        val rows = HoldingsOcr.parse("""
            我的基金
            总金额（元） 50,000.00
            昨日收益 +125.00
            华夏成长混合A 000001
            持有金额（元） 12,345.67
            持有收益 +234.50
            招商中证白酒指数(LOF)A
            161725
            金额（元） 8,888.88
            持有收益 -345.60
        """.trimIndent())
        assertEquals(2, rows.size)
        assertEquals("000001", rows[0].code)
        assertEquals(d("12345.67"), rows[0].amount)
        assertEquals(d("-345.60"), rows[1].profit)
        assertNull(rows[0].shares); assertNull(rows[0].cost)
    }
    @Test fun labelledDetailExtractsOnlyConfirmedQuantityAndUnitCost() {
        val row = HoldingsOcr.parse("""
            易方达蓝筹精选混合
            基金代码 005827
            持有份额（份）
            1,234.5678
            平均成本 1.2345
            单位净值 1.4567
            持有金额 1,798.39
            持有收益率 18.00%
        """.trimIndent()).single()
        assertEquals("005827", row.code)
        assertEquals(d("1234.5678"), row.shares)
        assertEquals(d("1.2345"), row.cost)
        assertNull(row.profit)
    }
    @Test fun keepsShareClassesAndFlagsTruncationWithoutGuessingCodes() {
        val rows = HoldingsOcr.parse("""
            华夏沪深300ETF联接A
            持有金额 1000.00
            华夏沪深300ETF联接C
            持有金额 2000.00
            易方达中证海外中国互联…
            持有金额 3000.00
        """.trimIndent())
        assertEquals(3, rows.size)
        assertTrue(rows[0].name.endsWith("A")); assertTrue(rows[1].name.endsWith("C"))
        assertTrue(rows.all { it.code == null })
        assertTrue(rows.last().issues.any { it.contains("截断") })
    }
    @Test fun usesPositionsToRejoinSplitLabelsAndNeverReadsPercentAsMoney() {
        val lines = listOf(OcrLine("华夏成长混合", 10, 10, 300, 40), OcrLine("000001", 500, 10, 650, 40),
            OcrLine("持有份额", 10, 80, 180, 110), OcrLine("123.4567", 400, 80, 650, 110),
            OcrLine("平均成本", 10, 140, 180, 170), OcrLine("1.23", 400, 140, 650, 170),
            OcrLine("持有收益率 12.34%", 10, 200, 600, 230))
        val row = HoldingsOcr.parse(lines).single()
        assertEquals("000001", row.code); assertEquals(d("123.4567"), row.shares); assertEquals(d("1.23"), row.cost); assertNull(row.profit)
    }
    @Test fun ignoresAccountSummaryAndDoesNotTreatSixDigitMoneyAsCode() {
        assertTrue(HoldingsOcr.parse("基金总资产\n100000.00\n累计收益 +123.45\n昨日收益 -20.00").isEmpty())
        val row = HoldingsOcr.parse("华夏成长混合\n持有金额 123456.78\n成本金额 100000.00").single()
        assertNull(row.code); assertNull(row.shares); assertNull(row.cost)
    }
    @Test fun malformedAndNegativeHoldingsRemainUnconfirmed() {
        val row = HoldingsOcr.parse("华夏成长混合\n持有份额 -10\n平均成本 --\n持有收益 −12.34").single()
        assertNull(row.shares); assertNull(row.cost); assertEquals(d("-12.34"), row.profit)
    }
    @Test fun selectedImportsAreAtomicAndExistingHoldingsNeverAccumulate() {
        val first = ReviewedHolding("000001", "华夏成长混合", d("100"), d("1.20"), "2026-11-01", ImportMode.HOLDING)
        val p = ReviewedImport.apply(Portfolio(), listOf(first), "default")
        assertEquals(d("100"), p.holdings.single().shares)
        assertThrows(IllegalArgumentException::class.java) { ReviewedImport.apply(p, listOf(first), "default") }
        val noChange = ReviewedImport.apply(p, listOf(first.copy(mode = ImportMode.WATCH)), "default")
        assertEquals(p, noChange)
        val updated = ReviewedImport.apply(p, listOf(first.copy(shares = d("120"), mode = ImportMode.UPDATE, confirmedDate = "2026-11-24")), "default")
        assertEquals(d("120"), updated.holdings.single().shares); assertEquals("CALIBRATE", updated.trades.single().kind)
        assertThrows(IllegalArgumentException::class.java) { ReviewedImport.apply(p, listOf(first.copy(mode = ImportMode.UPDATE, shares = null)), "default") }
        assertThrows(IllegalArgumentException::class.java) { ReviewedImport.apply(p, listOf(first, first), "default") }
        assertEquals(d("100"), p.holdings.single().shares)
    }
    @Test fun incompleteRowsRequireExplicitWatchOnlyAndCodecPreservesZeros() {
        val watch = ReviewedHolding("005827", "易方达蓝筹精选混合", null, null, "2026-11-24", ImportMode.WATCH)
        val p = ReviewedImport.apply(Portfolio(), listOf(watch), "default")
        assertEquals(BigDecimal.ZERO, p.holdings.single().shares)
        assertThrows(IllegalArgumentException::class.java) { ReviewedImport.apply(Portfolio(), listOf(watch.copy(mode = ImportMode.HOLDING)), "default") }
        assertEquals(listOf(watch), ReviewedImport.decode(ReviewedImport.encode(listOf(watch))))
        assertTrue(ReviewedImport.apply(Portfolio(), emptyList(), "default").holdings.isEmpty())
    }
    @Test fun sixDigitQuantityAndSummaryNeverBecomeFundCodes() {
        val row = HoldingsOcr.parse("总资产\n100000\n华夏成长混合A 000001\n持有份额\n100000\n平均成本 1.23\n持有金额 222.55\n持仓总金额 1000").single()
        assertEquals("000001", row.code); assertEquals(d("100000"), row.shares); assertEquals(d("222.55"), row.amount)
        assertNull(HoldingsOcr.parse("总资产\n100000\n华夏成长混合A\n持有金额222.55").single().code)
    }
    @Test fun availableSharesAndNeighbouringLabelsNeverReplaceTotalShares() {
        val row = HoldingsOcr.parse("华夏成长混合A 000001\n持有份额 100\n可用份额 40").single()
        assertEquals(d("100"), row.shares)
        val bad = HoldingsOcr.parse("华夏成长混合A 000001\n持有份额 昨日收益 +120.80\n平均成本 单位净值 1.97").single()
        assertNull(bad.shares); assertNull(bad.cost)
    }
    @Test fun ambiguousPunctuationIsNotTruncatedIntoAValidNumber() {
        val row = HoldingsOcr.parse("华夏成长混合A 000001\n持有份额 1.234.5678\n平均成本 12,34.56\n持有金额 1.524.16").single()
        assertNull(row.shares); assertNull(row.cost); assertNull(row.amount)
    }
    @Test fun explicitDateIsExtractedAndMissingDateStaysUnknown() {
        val row = HoldingsOcr.parse("华夏成长混合A 000001\n持仓确认日期 2026/11/01\n持有份额 100\n平均成本1.23").single()
        assertEquals("2026-11-01", row.confirmedDate)
        assertNull(HoldingsOcr.parse("华夏成长混合A 000001\n持有份额100").single().confirmedDate)
    }
    @Test fun actualEngineFixtureNeverInventsSharesFromDamagedCommas() {
        val raw = javaClass.getResource("/ocr/alipay-synthetic.txt")!!.readText()
        val rows = HoldingsOcr.parse(raw)
        assertEquals(2, rows.size); assertEquals(listOf("000001", "161725"), rows.map { it.code })
        assertNull(rows.first().shares); assertEquals(d("1.2345"), rows.first().cost)
        assertEquals(d("-345.60"), rows.last().profit)
    }
    @Test fun reviewDraftKeepsPartialEditsSelectionAndDateThroughRecreation() {
        val row = OcrDraftRow("华夏成长混合A", "000001", "12.", "", "2026-11-", 1, true, "金额 1,000.00")
        val draft = OcrReviewDraft("原始识别文字", "2026-11-24", listOf(row))
        assertEquals(draft, OcrDraftCodec.decode(OcrDraftCodec.encode(draft)))
        assertThrows(IllegalArgumentException::class.java) { OcrDraftCodec.decode("broken") }
    }
    @Test fun extractsCodeWithRiskLevelAndNavDate() {
        val raw = """
            资产详情
            国泰纳斯达克100指数(QDII)
            160213 中高风险 详情
            金额(元)
            1,371.34
            昨日收益(元)① 持有收益(元)① 持有收益率①
            -80.82 -268.66 -16.38%
            持有金额 1,371.34 待确认金额 0.00
            持仓成本价 4.4847 持有份额 365.69
            日涨幅 -5.57% 基金净值 3.7500(11-20)
        """.trimIndent()
        val row = HoldingsOcr.parse(raw).single()
        assertEquals("国泰纳斯达克100指数(QDII)", row.name)
        assertEquals("160213", row.code)
        assertEquals(d("365.69"), row.shares)
        assertEquals(d("4.4847"), row.cost)
        assertEquals(d("1371.34"), row.amount)
        assertEquals("2026-11-20", row.confirmedDate)
        assertTrue(row.issues.isEmpty())
    }
    @Test fun alipayBadgesAndNavDoNotCreatePhantomFunds() {
        // Representative engine output, with synthetic account values.
        val rows = HoldingsOcr.parse("""
            资产详情
            摩根标普500指数(QDIDA
            017641 中高风险“ES到指数基金名 详情
            金额(元)
            1220.00
            昨日收益(元) O 持有收益(元) O 持有收益率 中
            -12.17 -93.07 -9.04%
            持有人金额 1200.00 待确认金额 20.00
            持仓成本价 1.6886 持有份额 777.77
            日涨幅 -3.35% 基金净值 1.5360(11-20)
            预计11.26日 星期四 确认份额
            累计收益
            -100.00
        """.trimIndent())
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("017641", row.code)
        assertEquals(d("1200.00"), row.amount)
        assertEquals(d("777.77"), row.shares)
        assertEquals(d("1.6886"), row.cost)
        assertEquals(d("-93.07"), row.profit)
        assertEquals("2026-11-20", row.confirmedDate)
    }
    @Test fun badgeOnSeparateLineAndPendingOnlyAmountAreIgnored() {
        val rows = HoldingsOcr.parse("摩根标普500指数(QDII)A\n017641\n中高风险 金选 指数基金\n待确认金额 20.00\n基金净值 1.5360(11-20)")
        assertEquals(1, rows.size)
        assertNull(rows.single().amount)
        assertNull(rows.single().shares)
        assertNull(rows.single().cost)
    }
    @Test fun fundNameAndFirstAmountCanShareOneOcrRow() {
        val rows = HoldingsOcr.parse("华夏成长混合A 000001 持有金额 1000.00\n持有份额 500.00\n平均成本 2.00")
        assertEquals(1, rows.size)
        assertEquals("000001", rows.single().code)
        assertEquals(d("1000.00"), rows.single().amount)
        assertEquals(d("500.00"), rows.single().shares)
    }
    @Test fun riskBadgeOnNewFundRowNeverOverwritesPreviousHoldings() {
        val rows = HoldingsOcr.parse("华夏成长混合A 000001\n持有份额100\n平均成本1.2\n招商中证白酒指数A 161725 中高风险\n持有份额200\n平均成本2.3")
        assertEquals(2, rows.size)
        assertEquals(listOf("000001", "161725"), rows.map { it.code })
        assertEquals(listOf(d("100"), d("200")), rows.map { it.shares })
        assertEquals("招商中证白酒指数A", rows.last().name)
    }
}
