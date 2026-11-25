package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class FundEstimationTest {
    private fun d(s: String) = BigDecimal(s)
    private val day = "2026-11-25"
    private val previous = "2026-11-24"
    private fun nav(code: String = "000001", value: String = "1.00") = FundQuote(code, "测试", d(value), previous)
    private fun security(symbol: String, price: String, prev: String = "100", date: String = day,
        currency: String = "CNY", reference: String? = previous) = SecurityQuote(symbol, symbol, d(price), d(prev), date,
        "$date 14:00:00", currency, referenceDate = reference)
    private fun run(profile: FundProfile, securities: List<SecurityQuote>, q: FundQuote = nav(profile.code),
        profiles: Map<String, FundProfile> = emptyMap(), fundQuotes: Map<String, FundQuote> = emptyMap()) =
        FundEstimation.estimate(profile, q, securities.associateBy { it.symbol }, profiles + (profile.code to profile), fundQuotes + (q.code to q), day)
    private fun rate(result: EstimationResult, expected: String): FundEstimate {
        assertNotNull(result.reason, result.estimate)
        val e = result.estimate!!
        assertEquals(0, d(expected).compareTo(e.change.setScale(6, java.math.RoundingMode.HALF_UP)))
        return e
    }

    @Test fun equityUsesDisclosedWeightsAndModelsRemainderWithoutRenormalizing() {
        val p = FundProfile("000001", "股票", FundKind.EQUITY, disclosureDate = "2026-09-30", stockWeight = d("80"), cashWeight = d("20"),
            positions = listOf(FundPosition("sh600001", d("30"))))
        val e = rate(run(p, listOf(security("sh600001", "110"), security("sh000300", "102"))), "4.000000")
        assertTrue(e.complete)
        assertEquals(0, d("30").compareTo(e.directCoverage))
        assertTrue(e.note.contains("代理"))
    }
    @Test fun unknownAssetAllocationProducesPartialContributionExcludedFromTotals() {
        val p = FundProfile("000001", "混合", FundKind.HYBRID, disclosureDate = "2026-09-30",
            positions = listOf(FundPosition("sh600001", d("30"))))
        val e = rate(run(p, listOf(security("sh600001", "110"))), "3.000000")
        assertFalse(e.complete)
        assertEquals(0, d("30").compareTo(e.modeledCoverage))
    }
    @Test fun indexUsesActualTrackedIndexAndDisclosedStockAllocation() {
        val p = FundProfile("161725", "白酒指数", FundKind.INDEX, disclosureDate = "2026-09-30", stockWeight = d("95"), cashWeight = d("5"), benchmark = "sz399997")
        val e = rate(run(p, listOf(security("sz399997", "102"))), "1.900000")
        assertTrue(e.complete)
        assertEquals(EstimateMethod.INDEX, e.method)
    }
    @Test fun hybridCombinesStockAndBondExposuresAndKeepsLeverage() {
        val p = FundProfile("000001", "混合", FundKind.HYBRID, disclosureDate = "2026-09-30", stockWeight = d("60"), bondWeight = d("35"), cashWeight = d("5"))
        rate(run(p, listOf(security("sh000300", "102"), security("sh511010", "101"))), "1.550000")
        val bond = p.copy(kind = FundKind.BOND, stockWeight = BigDecimal.ZERO, bondWeight = d("120"), cashWeight = BigDecimal.ZERO)
        val e = rate(run(bond, listOf(security("sh511010", "101"))), "1.200000")
        assertTrue(e.note.contains("杠杆"))
    }
    @Test fun bondAndConvertibleUseDifferentMarketFactors() {
        val bond = FundProfile("000001", "纯债", FundKind.BOND, disclosureDate = "2026-09-30", bondWeight = d("100"))
        rate(run(bond, listOf(security("sh511010", "99"))), "-1.000000")
        val convertible = bond.copy(kind = FundKind.CONVERTIBLE)
        val e = rate(run(convertible, listOf(security("sh000832", "102"))), "2.000000")
        assertEquals(EstimateMethod.BOND, e.method)
    }
    @Test fun missingRequiredBondFactorDoesNotBecomeZeroReturn() {
        val p = FundProfile("000001", "混合", FundKind.HYBRID, disclosureDate = "2026-09-30", stockWeight = d("60"), bondWeight = d("40"))
        val result = run(p, listOf(security("sh000300", "102")))
        assertFalse(result.estimate?.complete == true)
        assertTrue(result.reason.contains("债券") || result.estimate?.note?.contains("债券") == true)
    }
    @Test fun moneyUsesPerTenThousandIncomeAndExpiresForecast() {
        val p = FundProfile("000198", "货币", FundKind.MONEY)
        val q = nav("000198").copy(kind = FundKind.MONEY, incomePerTenThousand = d("0.5"))
        val e = rate(run(p, emptyList(), q), "0.005000")
        assertEquals(EstimateMethod.MONEY, e.method)
        assertTrue(e.note.contains(previous))
        assertNull(run(p, emptyList(), q.copy(date = "2026-11-10")).estimate)
        val h = Holding("h", q.code, q.name, shares = d("10000"))
        assertEquals(d("0.50"), MarketMath.daily(h, q.copy(date = day), day)!!.amount)
        assertFalse(MarketMath.daily(h, q.copy(date = day), day)!!.estimated)
    }
    @Test fun floatingMoneyKeepsNavAndUsesAnnualYieldForForecast() {
        val p = FundProfile("000001", "浮动净值货币", FundKind.FLOATING_MONEY, annualYield = d("3.65"))
        val e = rate(run(p, emptyList(), nav(value = "100.50")), "0.010000")
        assertEquals(d("100.50"), e.basisPrice)
    }
    @Test fun commodityLinkUsesCnyTargetPriceProxyWithNoDoubleFx() {
        val p = FundProfile("000216", "黄金联接", FundKind.COMMODITY, disclosureDate = "2026-09-30", fundWeight = d("95"), cashWeight = d("5"), target = "sh518880")
        val e = rate(run(p, listOf(security("sh518880", "102"))), "1.900000")
        assertEquals(EstimateMethod.PROXY, e.method)
        assertTrue(e.note.contains("溢价"))
    }
    @Test fun etfAndReitUsePreviousMarketCloseInsteadOfOldOfficialNav() {
        for (kind in listOf(FundKind.ETF, FundKind.REIT)) {
            val p = FundProfile("180101", "场内", kind, listed = "sz180101")
            val e = rate(run(p, listOf(security("sz180101", "102")), nav("180101", "2")), "2.000000")
            assertEquals(d("100"), e.basisPrice)
            assertEquals(EstimateMethod.MARKET, e.method)
        }
    }
    @Test fun qdiiCompoundsMatchedFxAndRetainsForeignValuationDate() {
        val p = FundProfile("270042", "海外", FundKind.QDII, currency = "CNY", foreign = true, benchmark = "usQQQ", stockWeight = d("100"), disclosureDate = "2026-09-30")
        val asset = security("usQQQ", "110", date = previous, currency = "USD", reference = "2026-11-23")
        val fx = security("FX:USD:CNY:$previous", "102", date = previous, reference = "2026-11-23")
        val e = rate(run(p, listOf(asset, fx)), "12.200000")
        assertEquals(previous, e.date)
        assertEquals("2026-11-23", e.referenceDate)
        assertEquals(EstimateMethod.OVERSEAS, e.method)
        val mismatch = fx.copy(referenceDate = "2026-11-20")
        assertNull(run(p, listOf(asset, mismatch)).estimate)
        assertNull(run(p, listOf(asset)).estimate)
    }
    @Test fun usdShareNeedsNoFxButCnyProxyNeedsConversion() {
        val p = FundProfile("270042", "海外美元", FundKind.QDII, currency = "USD", foreign = true, benchmark = "usQQQ", stockWeight = d("100"), disclosureDate = "2026-09-30")
        rate(run(p, listOf(security("usQQQ", "110", date = previous, currency = "USD", reference = "2026-11-23"))), "10.000000")
        assertNull(run(p.copy(benchmark = null, target = "sz159941", stockWeight = null, fundWeight = d("100")), listOf(security("sz159941", "110"))).estimate)
    }
    @Test fun staleOrFutureSecuritiesAndFutureDisclosuresNeverProduceTodayProfit() {
        val p = FundProfile("000001", "指数", FundKind.INDEX, benchmark = "sh000300", stockWeight = d("100"), disclosureDate = "2026-09-30")
        assertNull(run(p, listOf(security("sh000300", "110", date = previous))).estimate)
        assertNull(run(p, listOf(security("sh000300", "110", date = "2026-11-26"))).estimate)
        assertNull(run(p.copy(disclosureDate = "2026-12-31"), listOf(security("sh000300", "110"))).estimate)
        assertNull(run(p.copy(disclosureDate = "2025-01-01"), listOf(security("sh000300", "110"))).estimate)
    }
    @Test fun fofUsesChildWeightsAndPropagatesPartialAndCycles() {
        val parent = FundProfile("006580", "FOF", FundKind.FOF, disclosureDate = "2026-09-30", fundWeight = d("80"), cashWeight = d("20"),
            positions = listOf(FundPosition("000002", d("80"), PositionKind.FUND)))
        val child = FundProfile("000002", "子基金", FundKind.INDEX, disclosureDate = "2026-09-30", stockWeight = d("100"), benchmark = "sh000300")
        val e = rate(run(parent, listOf(security("sh000300", "110")), profiles = mapOf(child.code to child), fundQuotes = mapOf(child.code to nav(child.code))), "8.000000")
        assertTrue(e.complete)
        val incomplete = parent.copy(fundWeight = d("100"), cashWeight = BigDecimal.ZERO)
        assertFalse(run(incomplete, listOf(security("sh000300", "110")), profiles = mapOf(child.code to child), fundQuotes = mapOf(child.code to nav(child.code))).estimate!!.complete)
        val self = parent.copy(positions = listOf(FundPosition(parent.code, d("80"), PositionKind.FUND)))
        assertNull(run(self, emptyList()).estimate)
    }
    @Test fun officialAndProviderValuesTakePriorityOverLocalModel() {
        val p = FundProfile("000001", "指数", FundKind.INDEX, benchmark = "sh000300", stockWeight = d("100"), disclosureDate = "2026-09-30")
        val modeled = rate(run(p, listOf(security("sh000300", "110"))), "10.000000")
        val h = Holding("h", p.code, p.name, shares = d("100"))
        val q = nav().copy(model = modeled)
        assertEquals(d("10.00"), MarketMath.daily(h, q, day)!!.amount)
        assertEquals(d("2.00"), MarketMath.daily(h, q.copy(estimate = d("1.02"), estimateTime = "$day 14:00"), day)!!.amount)
        assertEquals(d("2.00"), MarketMath.daily(h, q.copy(nav = d("1.02"), change = d("2"), date = day), day)!!.amount)
        assertNull(MarketMath.daily(h, q, "2026-11-26"))
    }
    @Test fun incompleteModelIsNotAFullDailyProfit() {
        val p = FundProfile("000001", "股票", FundKind.EQUITY, disclosureDate = "2026-09-30", positions = listOf(FundPosition("sh600001", d("30"))))
        val e = run(p, listOf(security("sh600001", "110"))).estimate!!
        assertNull(MarketMath.daily(Holding("h", p.code, p.name, shares = d("100")), nav().copy(model = e), day))
    }
    @Test fun cacheRoundTripPreservesModelAndMoneyIncomeWithoutDoubleNormalization() {
        val p = FundProfile("000198", "货币", FundKind.MONEY)
        val q = nav("000198").copy(kind = FundKind.MONEY, incomePerTenThousand = d("0.5"))
        val e = run(p, emptyList(), q).estimate!!
        val cached = q.copy(model = e, estimateReason = "测试")
        assertEquals(cached, MarketParser.quotes(MarketParser.encodeQuotes(listOf(cached))).single())
    }
    @Test fun monetaryWireNavIsIncomeAndNotPriceFromFirstParse() {
        val q = MarketParser.quotes("""{"Datas":[{"FCODE":"000198","SHORTNAME":"天弘余额宝货币","NAV":"0.2185","PDATE":"2026-11-24","NAVCHGRT":"--"}]}""").single()
        assertEquals(BigDecimal.ONE, q.nav)
        assertEquals(d("0.2185"), q.incomePerTenThousand)
        assertEquals(FundKind.MONEY, q.kind)
    }
    @Test fun linkingFundModelsBothStockAndFundExposureExactlyOnce() {
        val p = FundProfile("000216", "黄金联接", FundKind.COMMODITY, disclosureDate = "2026-09-30", stockWeight = BigDecimal.ZERO,
            fundWeight = d("95"), cashWeight = d("5"), target = "sh518880")
        val e = rate(run(p, listOf(security("sh518880", "102"))), "1.900000")
        assertTrue(e.complete)
        assertEquals(0, d("100").compareTo(e.modeledCoverage))
    }
    @Test fun foreignBondProxyUsesForeignReturnAndSameIntervalFx() {
        val p = FundProfile("007360", "美元债人民币", FundKind.BOND, foreign = true, disclosureDate = "2026-09-30", bondWeight = d("100"), bondProxy = "usBND")
        val e = rate(run(p, listOf(security("usBND", "101", date = previous, currency = "USD", reference = "2026-11-23"),
            security("FX:USD:CNY:$previous", "102", date = previous, reference = "2026-11-23"))), "3.020000")
        assertEquals(previous, e.date)
    }

    @Test fun differentForeignShareCurrenciesCrossThroughCnyWithMatchedIntervals() {
        val p = FundProfile("000001", "欧洲美元份额", FundKind.QDII, currency = "USD", foreign = true, benchmark = "yf:^GDAXI", stockWeight = d("100"), disclosureDate = "2026-09-30")
        val asset = security("yf:^GDAXI", "110", date = previous, currency = "EUR", reference = "2026-11-23")
        val eur = security("FX:EUR:CNY:$previous", "102", date = previous, reference = "2026-11-23")
        val usd = security("FX:USD:CNY:$previous", "100", date = previous, reference = "2026-11-23")
        rate(run(p, listOf(asset, eur, usd)), "12.200000")
    }
    @Test fun mismatchedDirectStockCanBeReplacedByDisclosedBroadProxy() {
        val p = FundProfile("000001", "股票", FundKind.HYBRID, disclosureDate = "2026-09-30", stockWeight = d("100"),
            positions = listOf(FundPosition("sh600001", d("30")), FundPosition("hk00700", d("20"))))
        val e = rate(run(p, listOf(security("sh600001", "110", reference = null), security("hk00700", "105", reference = previous),
            security("sh000300", "102", reference = null))), "4.400000")
        assertTrue(e.complete)
    }

    @Test fun bondIndexExposureIsNotCountedAgainAsSeparateBondProxy() {
        val p = FundProfile("000001", "国债指数", FundKind.INDEX, disclosureDate = "2026-09-30", bondWeight = d("100"), benchmark = "sh000012")
        val e = rate(run(p, listOf(security("sh000012", "102"), security("sh511010", "103"))), "2.000000")
        assertTrue(e.complete)
    }

    @Test fun fractionalWeightedModelSurvivesTheActualQuoteCacheCodec() {
        val p = FundProfile("000001", "股票", FundKind.HYBRID, disclosureDate = "2026-09-30", stockWeight = d("100"),
            positions = listOf(FundPosition("sh600001", d("31.23"))))
        val e = run(p, listOf(security("sh600001", "101", "99"), security("sh000300", "102", "99"))).estimate!!
        val restored = MarketParser.quotes(MarketParser.encodeQuotes(listOf(nav().copy(model = e)))).single()
        assertNotNull("Weighted estimate disappeared when cached", restored.model)
        assertEquals(e, restored.model)
    }

    @Test fun mixedFundEquityBenchmarkIsNeverAppliedToItsBondSlice() {
        val p = FundProfile("000001", "混合", FundKind.HYBRID, disclosureDate = "2026-09-30", stockWeight = d("60"), bondWeight = d("40"), benchmark = "sh000300")
        rate(run(p, listOf(security("sh000300", "102"), security("sh511010", "101"))), "1.600000")
    }
    @Test fun fofPublishedChildrenNeedKnownMatchingReferenceDates() {
        val p = FundProfile("006580", "FOF", FundKind.FOF, disclosureDate = "2026-09-30", fundWeight = d("100"),
            positions = listOf(FundPosition("000002", d("50"), PositionKind.FUND), FundPosition("000003", d("50"), PositionKind.FUND)))
        val children = mapOf("000002" to nav("000002").copy(date = day, change = d("2")), "000003" to nav("000003").copy(date = day, change = d("3")))
        val e = run(p, emptyList(), fundQuotes = children)
        assertFalse("Unknown valuation intervals were treated as complete", e.estimate?.complete == true)
        assertTrue(e.reason.contains("区间") || e.estimate?.note?.contains("区间") == true)
    }
    @Test fun fullyCoveredFofCanHaveGenuinelyZeroReturn() {
        val p = FundProfile("006580", "FOF", FundKind.FOF, disclosureDate = "2026-09-30", fundWeight = d("100"),
            positions = listOf(FundPosition("000002", d("50"), PositionKind.FUND), FundPosition("000003", d("50"), PositionKind.FUND)))
        val children = mapOf("000002" to nav("000002").copy(date = day, change = d("2"), previousDate = previous),
            "000003" to nav("000003").copy(date = day, change = d("-2"), previousDate = previous))
        assertTrue(rate(run(p, emptyList(), fundQuotes = children), "0.000000").complete)
    }
    @Test fun listedMarketReturnSupersedesSameDayNavWithoutReplacingNav() {
        val p = FundProfile("510300", "ETF", FundKind.ETF, listed = "sh510300")
        val e = run(p, listOf(security("sh510300", "102")), nav("510300", "2")).estimate!!
        val q = nav("510300", "2").copy(kind = FundKind.ETF, date = day, change = d("5"), model = e)
        assertEquals(d("200.00"), MarketMath.daily(Holding("h", q.code, q.name, shares = d("100")), q, day)!!.amount)
        assertEquals(d("2"), q.nav)
    }

    @Test fun overseasFofConvertsChildCurrencyAndKeepsItsForeignSessionDate() {
        val p = FundProfile("006580", "海外FOF", FundKind.FOF, foreign = true, disclosureDate = "2026-09-30", fundWeight = d("100"),
            positions = listOf(FundPosition("000002", d("100"), PositionKind.FUND)))
        val child = FundProfile("000002", "美元指数", FundKind.QDII, currency = "USD", foreign = true, disclosureDate = "2026-09-30", stockWeight = d("100"), benchmark = "usSPY")
        val e = rate(run(p, listOf(security("usSPY", "110", date = previous, currency = "USD", reference = "2026-11-23"),
            security("FX:USD:CNY:$previous", "102", date = previous, reference = "2026-11-23")), nav(p.code).copy(date = "2026-11-23"),
            mapOf(child.code to child), mapOf(child.code to nav(child.code).copy(currency = "USD", date = "2026-11-23"))), "12.200000")
        assertEquals(previous, e.date)
        assertTrue(e.complete)
    }
    @Test fun fofListedChildUsesMarketReturnInsteadOfNavReturn() {
        val p = FundProfile("006580", "FOF", FundKind.FOF, disclosureDate = "2026-09-30", fundWeight = d("100"),
            positions = listOf(FundPosition("510300", d("100"), PositionKind.FUND)))
        val child = FundProfile("510300", "ETF", FundKind.ETF, listed = "sh510300")
        rate(run(p, listOf(security("sh510300", "102")), profiles = mapOf(child.code to child),
            fundQuotes = mapOf(child.code to nav(child.code).copy(kind = FundKind.ETF, date = day, change = d("5"), previousDate = previous))), "2.000000")
    }

}
