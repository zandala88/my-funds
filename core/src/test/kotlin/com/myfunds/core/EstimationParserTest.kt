package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class EstimationParserTest {
    @Test fun classificationCoversAllCategoriesIncludingForeignSubtypesAndCurrency() {
        val cases = listOf("股票型" to FundKind.EQUITY, "指数型-股票" to FundKind.INDEX, "混合型-灵活" to FundKind.HYBRID,
            "债券型-长债" to FundKind.BOND, "债券型-可转债" to FundKind.CONVERTIBLE, "货币型-普通货币" to FundKind.MONEY,
            "货币型-浮动净值" to FundKind.FLOATING_MONEY, "指数型-海外股票" to FundKind.QDII, "QDII-纯债" to FundKind.BOND, "QDII-REITs" to FundKind.QDII,
            "FOF-稳健" to FundKind.FOF, "QDII-商品" to FundKind.COMMODITY, "Reits" to FundKind.REIT)
        for ((type, expected) in cases) {
            val profile = EstimationParser.basic("""{"Datas":{"FCODE":"000001","SHORTNAME":"测试美元A","FTYPE":"$type"}}""")
            assertEquals(type, expected, profile.kind)
            assertEquals("USD", profile.currency)
        }
        val etf = EstimationParser.basic("""{"Datas":{"FCODE":"510300","SHORTNAME":"沪深300ETF","FTYPE":"指数型-股票","ISLIST":"1","LISTTEXCHMARK":"SH"}}""")
        assertEquals(FundKind.ETF, etf.kind)
        assertEquals("sh510300", etf.listed)
    }
    @Test fun basicMapsChineseAndForeignIndexSymbolsWithoutGuessingUnknownCodes() {
        val p = EstimationParser.basic("""{"Datas":{"FCODE":"161725","SHORTNAME":"白酒指数","FTYPE":"指数型-股票","INDEXCODE":"399997","NEWINDEXTEXCH":"0"}}""")
        assertEquals("sz399997", p.benchmark)
        val foreign = EstimationParser.basic("""{"Datas":{"FCODE":"270042","SHORTNAME":"纳指联接人民币A","FTYPE":"指数型-海外股票","INDEXCODE":"NDX100","NEWINDEXTEXCH":"100"}}""")
        assertEquals("usQQQ", foreign.benchmark)
        assertTrue(foreign.foreign)
        val unknown = EstimationParser.basic("""{"Datas":{"FCODE":"000001","SHORTNAME":"未知","FTYPE":"其他","INDEXCODE":"XYZ"}}""")
        assertNull(unknown.benchmark)
    }
    @Test fun allocationRetainsLeverageAndMissingValues() {
        val p = FundProfile("000001", "债券", FundKind.BOND)
        val result = EstimationParser.allocation(p, """{"Datas":[{"FSRQ":"2026-09-30","GP":"--","ZQ":"118.47","HB":"0.06","JJ":"--"}]}""")
        assertEquals(BigDecimal("118.47"), result.bondWeight)
        assertNull(result.stockWeight)
        assertEquals("2026-09-30", result.allocationDate)
    }
    @Test fun positionsPreserveRealStockAndFundWeightsAndTarget() {
        val p = FundProfile("006580", "FOF", FundKind.FOF)
        val result = EstimationParser.positions(p, """{"Success":true,"Expansion":"2026-09-30","Datas":{"fundStocks":[{"GPDM":"600519","GPJC":"茅台","JZBL":"18.54","NEWTEXCH":"1"},{"GPDM":"00700","GPJC":"腾讯","JZBL":"5.1","TEXCH":"116"},{"GPDM":"AAPL","GPJC":"苹果","JZBL":"2","TEXCH":"105"}],"fundfofs":[{"TZJJDM":"006985","TZJJMC":"债券A","ZJZBL":"7.85","RZDF":"99"}],"fundboods":[{"ZQDM":"2600006","ZQMC":"国债","ZJZBL":"7.12"}],"ETFCODE":"159941"}}""")
        assertEquals(listOf("sh600519", "hk00700", "usAAPL", "006985"), result.positions.map { it.symbol })
        assertEquals(PositionKind.FUND, result.positions.last().kind)
        assertEquals(BigDecimal("7.85"), result.positions.last().weight)
        assertEquals("sz159941", result.target)
        assertEquals("2026-09-30", result.disclosureDate)
    }
    private fun wire(symbol: String, price: String, previous: String, time: String): String {
        val fields = MutableList(40) { "" }; fields[1] = "测试"; fields[3] = price; fields[4] = previous; fields[30] = time
        return "v_$symbol=\"${fields.joinToString("~")}\";"
    }
    @Test fun securitiesUseMarketLocalDatesAndRejectMalformedPricesAndTimes() {
        val raw = wire("sh600519", "110", "100", "20261125140000") + wire("usAAPL", "110", "100", "2026-11-24 16:00:02") +
            wire("hk00700", "110", "100", "2026/11/25 13:50:00") + wire("sz000001", "--", "100", "20261125140000") +
            wire("sz000002", "110", "0", "20261125140000") + wire("sz000003", "110", "100", "20269999140000")
        val rows = EstimationParser.tencent(raw)
        assertEquals(3, rows.size)
        assertEquals("2026-11-24", rows.single { it.symbol == "usAAPL" }.date)
        assertEquals("USD", rows.single { it.symbol == "usAAPL" }.currency)
        assertEquals("America/New_York", rows.single { it.symbol == "usAAPL" }.zone)
        assertEquals("HKD", rows.single { it.symbol == "hk00700" }.currency)
    }
    @Test fun yahooHistorySuppliesDatedReturnIntervalsAndSkipsMissingBars() {
        val raw = """{"chart":{"result":[{"meta":{"exchangeTimezoneName":"Europe/London","currency":"CNY"},"timestamp":[1795392000,1795478400,1795564800],"indicators":{"quote":[{"close":[6.6,6.7,null]}]}}],"error":null}}"""
        val rows = EstimationParser.yahoo(raw, "FX:USD:CNY", "CNY")
        assertEquals(1, rows.size)
        assertEquals("FX:USD:CNY:2026-11-24", rows.single().symbol)
        assertEquals("2026-11-23", rows.single().referenceDate)
        assertEquals("2026-11-24", rows.single().date)
    }
    @Test fun profileCacheRoundTripPreservesDatesAndPositions() {
        val p = FundProfile("000001", "测试", FundKind.HYBRID, disclosureDate = "2026-09-30", stockWeight = BigDecimal("80"), bondWeight = BigDecimal("20"),
            allocationDate = "2026-09-30", positions = listOf(FundPosition("hk00700", BigDecimal("30"))))
        assertEquals(p, EstimationParser.decodeProfile(EstimationParser.encodeProfile(p)))
    }
    @Test fun jsonpEstimateRequiresValidCallbackDateAndPositiveNumbers() {
        val q = EstimationParser.provider("jsonpgz({\"fundcode\":\"000001\",\"name\":\"测试\",\"jzrq\":\"2026-11-24\",\"dwjz\":\"1\",\"gsz\":\"1.02\",\"gszzl\":\"2\",\"gztime\":\"2026-11-25 14:00\"});")
        assertEquals(BigDecimal("1.02"), q.estimate)
        assertThrows(IllegalArgumentException::class.java) { EstimationParser.provider("alert(1)") }
        assertThrows(IllegalArgumentException::class.java) { EstimationParser.provider("jsonpgz({\"fundcode\":\"000001\",\"dwjz\":\"0\"});") }
    }
    @Test fun overseasBenchmarksCoverJapanEuropeAndCommodityProxies() {
        val cases = listOf("日经225指数" to "yf:^N225", "德国DAX指数" to "yf:^GDAXI", "英国富时100指数" to "yf:^FTSE", "标普石油天然气上游股票指数" to "usXOP", "原油期货价格" to "usUSO")
        for ((name, symbol) in cases) {
            val p = EstimationParser.basic("""{"Datas":{"FCODE":"000001","SHORTNAME":"海外基金","FTYPE":"指数型-海外股票","INDEXNAME":"$name"}}""")
            assertEquals(name, symbol, p.benchmark)
        }
    }
    @Test fun invalidClockAndMissingHistoryBarCannotCreateFakeReturnInterval() {
        assertTrue(EstimationParser.tencent(wire("sh600519", "110", "100", "20260230140000")).isEmpty())
        val raw = """{"chart":{"result":[{"meta":{"exchangeTimezoneName":"UTC"},"timestamp":[1795392000,1795478400,1795564800],"indicators":{"quote":[{"close":[6.6,null,6.8]}]}}]}}"""
        assertTrue(EstimationParser.yahoo(raw, "FX:USD:CNY", "CNY").isEmpty())
    }

    @Test fun listedGoldAndConvertibleEtfsKeepMarketPriceClassification() {
        for (name in listOf("黄金ETF华安", "可转债ETF博时", "货币ETF")) {
            val p = EstimationParser.basic("""{"Datas":{"FCODE":"518880","SHORTNAME":"$name","FTYPE":"指数型-其他","ISLIST":"1","LISTTEXCHMARK":"SH"}}""")
            assertEquals(FundKind.ETF, p.kind)
        }
    }

    @Test fun DollarBondAssetNameDoesNotTurnRenminbiSharesIntoDollars() {
        val p = EstimationParser.basic("""{"Datas":{"FCODE":"007360","SHORTNAME":"易方达中短期美元债(QDII)A人民币","FTYPE":"QDII-纯债"}}""")
        assertEquals("CNY", p.currency)
        val q = MarketParser.quotes("""{"Datas":[{"FCODE":"007360","SHORTNAME":"易方达中短期美元债(QDII)A人民币","NAV":"1","PDATE":"2026-11-24"}]}""").single()
        assertEquals("CNY", q.currency)
    }

}
