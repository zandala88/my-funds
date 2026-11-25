package com.myfunds.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object EstimationParser {
    private fun n(o: JSONObject, key: String) = MarketParser.number(o.opt(key))
    private fun t(o: JSONObject, key: String) = MarketParser.text(o, key)
    private fun date(s: String?) = s?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
    private fun data(raw: String): JSONObject = MarketParser.root(raw).optJSONObject("Datas") ?: throw IllegalArgumentException("基金资料暂无数据")
    fun kind(name: String, type: String = "", listed: Boolean = false): FundKind {
        val text = "$name $type"
        return when {
            type.equals("Reits", true) || (listed && text.contains("REIT", true) && !text.contains("QDII", true) && !type.contains("海外")) -> FundKind.REIT
            listed && name.contains("ETF", true) && !name.contains("联接") -> FundKind.ETF
            text.contains("浮动净值") && text.contains("货币") -> FundKind.FLOATING_MONEY
            type.contains("货币") || (type.isBlank() && name.contains("货币")) -> FundKind.MONEY
            text.contains("FOF", true) || type.contains("基金中基金") -> FundKind.FOF
            text.contains("可转债") || text.contains("转债") -> FundKind.CONVERTIBLE
            text.contains("黄金") || type.contains("商品") || text.contains("原油") || text.contains("白银") -> FundKind.COMMODITY
            type.contains("债") -> FundKind.BOND
            text.contains("QDII", true) || type.contains("海外") -> FundKind.QDII
            type.contains("指数") || (type.isBlank() && name.contains("指数")) -> FundKind.INDEX
            type.contains("混合") || (type.isBlank() && name.contains("混合")) -> FundKind.HYBRID
            type.contains("股票") || (type.isBlank() && name.contains("股票")) -> FundKind.EQUITY
            type.isBlank() && name.contains("债券") -> FundKind.BOND
            else -> FundKind.UNKNOWN
        }
    }
    fun currency(name: String): String = when {
        name.contains("人民币") || name.contains("RMB", true) -> "CNY"
        Regex("(美元|USD)(现汇|现钞|份额)?[A-Z]?$", RegexOption.IGNORE_CASE).containsMatchIn(name.replace("(", "").replace(")", "").trim()) -> "USD"
        name.contains("港币") || name.contains("港元") -> "HKD"
        name.contains("欧元") && !name.contains("欧元债") -> "EUR"
        name.contains("日元") -> "JPY"
        name.contains("英镑") -> "GBP"
        else -> "CNY"
    }
    fun listedCandidate(code: String, name: String): Boolean =
        code.firstOrNull() in listOf('1', '5') && ((name.contains("ETF", true) && !name.contains("联接")) || name.contains("REIT", true))
    fun symbol(code: String?, market: String? = null): String? {
        if (code == null || !code.matches(Regex("[A-Za-z0-9.]{1,16}"))) return null
        return when {
            code.matches(Regex("[0-9]{5}")) -> "hk$code"
            code.matches(Regex("[0-9]{6}")) && market in listOf("1", "SH") -> "sh$code"
            code.matches(Regex("[0-9]{6}")) && market in listOf("0", "2", "SZ") -> "sz$code"
            code.matches(Regex("[0-9]{6}")) && code.first() in "569" -> "sh$code"
            code.matches(Regex("[0-9]{6}")) && code.first() in "0123" -> "sz$code"
            code.matches(Regex("[A-Za-z][A-Za-z0-9.]{0,12}")) -> "us${code.uppercase()}"
            else -> null
        }
    }
    private fun benchmark(code: String?, market: String?, name: String): String? = when {
        code == "NDX100" || name.contains("纳斯达克100") -> "usQQQ"
        code in listOf("SPX", "SP500", "SPX500") || name.contains("标普500") -> "usSPY"
        code in listOf("HSI", "HSCEI", "HSTECH") -> "hk$code"
        name.contains("日经") -> "yf:^N225"
        name.contains("德国") || name.contains("DAX", true) -> "yf:^GDAXI"
        name.contains("英国") || name.contains("富时100") -> "yf:^FTSE"
        name.contains("法国") -> "yf:^FCHI"
        name.contains("印度") -> "yf:^BSESN"
        name.contains("石油天然气") -> "usXOP"
        name.contains("原油") -> "usUSO"
        name.contains("黄金") -> "usGLD"
        name.contains("白银") -> "usSLV"
        name.contains("REIT", true) -> "usVNQ"
        code?.matches(Regex("[0-9]{6}")) == true && market in listOf("0", "1", "2") -> symbol(code, market)
        else -> null
    }
    fun basic(raw: String): FundProfile {
        val o = data(raw)
        val code = t(o, "FCODE")?.takeIf { it.matches(Regex("[0-9]{6}")) } ?: throw IllegalArgumentException("基金代码无效")
        val name = (t(o, "SHORTNAME") ?: code).take(200)
        val type = (t(o, "FTYPE") ?: "").take(100)
        val listed = t(o, "ISLIST") == "1" || type.equals("Reits", true)
        val kind = kind(name, type, listed)
        val currency = currency(name)
        val foreign = name.contains("QDII", true) || type.contains("QDII", true) || type.contains("海外")
        val bondProxy = when {
            foreign -> "usBND"
            name.contains("短") || name.contains("同业存单") -> "sh511360"
            name.contains("信用") || name.contains("城投") || name.contains("企业") || name.contains("公司债") -> "sh511220"
            else -> "sh511010"
        }
        return FundProfile(code, name, kind, currency, foreign,
            benchmark = benchmark(t(o, "INDEXCODE"), t(o, "NEWINDEXTEXCH") ?: t(o, "INDEXTEXCH"), t(o, "INDEXNAME") ?: t(o, "BENCH") ?: name),
            listed = if (listed) symbol(code, t(o, "LISTTEXCHMARK") ?: t(o, "NEWTEXCH") ?: t(o, "LISTTEXCH")) else null,
            annualYield = if (kind in listOf(FundKind.MONEY, FundKind.FLOATING_MONEY)) n(o, "SYI") ?: n(o, "SYL_7D") else null,
            typeName = type, bondProxy = bondProxy)
    }
    fun allocation(p: FundProfile, raw: String): FundProfile {
        val a = MarketParser.root(raw).optJSONArray("Datas") ?: return p
        val o = (0 until a.length().coerceAtMost(100)).mapNotNull { a.optJSONObject(it) }
            .filter { date(t(it, "FSRQ")) != null }.maxByOrNull { t(it, "FSRQ")!! } ?: return p
        fun weight(key: String) = n(o, key)?.takeIf { it.signum() >= 0 && it <= BigDecimal("300") }
        return p.copy(allocationDate = date(t(o, "FSRQ")), stockWeight = weight("GP"), bondWeight = weight("ZQ"),
            fundWeight = weight("JJ"), cashWeight = weight("HB"), otherWeight = weight("QT"))
    }
    fun positions(p: FundProfile, raw: String): FundProfile {
        val root = MarketParser.root(raw)
        val o = root.optJSONObject("Datas") ?: return p
        val positions = mutableListOf<FundPosition>()
        val stocks = o.optJSONArray("fundStocks") ?: JSONArray()
        for (i in 0 until stocks.length().coerceAtMost(1000)) {
            val row = stocks.optJSONObject(i) ?: continue
            val symbol = symbol(t(row, "GPDM"), t(row, "NEWTEXCH") ?: t(row, "TEXCH")) ?: continue
            val weight = n(row, "JZBL")?.takeIf { it.signum() >= 0 && it <= BigDecimal("300") } ?: continue
            positions.add(FundPosition(symbol, weight))
        }
        val funds = o.optJSONArray("fundfofs") ?: JSONArray()
        for (i in 0 until funds.length().coerceAtMost(1000)) {
            val row = funds.optJSONObject(i) ?: continue
            val code = t(row, "TZJJDM")?.takeIf { it.matches(Regex("[0-9]{6}")) } ?: continue
            val weight = n(row, "ZJZBL")?.takeIf { it.signum() >= 0 && it <= BigDecimal("300") } ?: continue
            positions.add(FundPosition(code, weight, PositionKind.FUND))
        }
        val unique = positions.groupBy { it.kind to it.symbol }.map { (_, rows) -> rows.first().copy(weight = rows.fold(BigDecimal.ZERO) { v, r -> v + r.weight }) }
        return p.copy(disclosureDate = date(t(root, "Expansion")), positions = unique, target = symbol(t(o, "ETFCODE")))
    }
    fun tencent(raw: String): List<SecurityQuote> = Regex("v_([a-zA-Z0-9.]+)=\"([^\"]*)\";").findAll(raw.take(4 * 1024 * 1024)).mapNotNull { match ->
        val symbol = match.groupValues[1]
        if (!symbol.matches(Regex("(sh|sz|hk|us)[A-Za-z0-9.]{1,16}"))) return@mapNotNull null
        val f = match.groupValues[2].split('~')
        val price = f.getOrNull(3)?.let(MarketParser::number)?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
        val previous = f.getOrNull(4)?.let(MarketParser::number)?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
        val time = f.getOrNull(30) ?: return@mapNotNull null
        val parsed = runCatching {
            LocalDateTime.parse(time.replace('/', '-'), if (time.matches(Regex("[0-9]{14}"))) DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT) else DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
        }.getOrNull() ?: return@mapNotNull null
        val currency = when { symbol.startsWith("us") -> "USD"; symbol.startsWith("hk") -> "HKD"; else -> "CNY" }
        SecurityQuote(symbol, f.getOrNull(1)?.take(200) ?: symbol, price, previous, parsed.toLocalDate().toString(), parsed.toString().replace('T', ' '), currency,
            zone = if (currency == "USD") "America/New_York" else "Asia/Shanghai")
    }.toList()

    fun yahoo(raw: String, symbol: String, currency: String): List<SecurityQuote> {
        val root = JSONObject(raw).optJSONObject("chart") ?: throw IllegalArgumentException("缺少日线行情")
        val o = root.optJSONArray("result")?.optJSONObject(0) ?: throw IllegalArgumentException("日线行情不可用")
        val meta = o.optJSONObject("meta") ?: JSONObject()
        val zone = runCatching { ZoneId.of(t(meta, "exchangeTimezoneName") ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
        val stamps = o.optJSONArray("timestamp") ?: return emptyList()
        val prices = o.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return emptyList()
        val points = (0 until stamps.length().coerceAtMost(1000)).map { i ->
            val value = MarketParser.number(prices.opt(i))?.takeIf { it.signum() > 0 } ?: return@map null
            val seconds = stamps.optLong(i, 0).takeIf { it > 0 } ?: return@map null
            val time = Instant.ofEpochSecond(seconds).atZone(zone)
            Triple(time.toLocalDate().toString(), value, time.toLocalDateTime().toString().replace('T', ' '))
        }
        return points.zipWithNext().mapNotNull { (previous, point) ->
            if (previous == null || point == null || previous.first >= point.first) return@mapNotNull null
            val marketTime = meta.optLong("regularMarketTime", 0).takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone) }
            val asOf = if (marketTime?.toLocalDate()?.toString() == point.first) marketTime.toLocalDateTime().toString().replace('T', ' ') else point.third
            SecurityQuote(if (symbol.startsWith("FX:")) "$symbol:${point.first}" else symbol,
                t(meta, "shortName") ?: symbol, point.second, previous.second, point.first, asOf,
                if (symbol.startsWith("FX:")) currency else t(meta, "currency") ?: currency,
                previous.first, zone.id, "Yahoo 日线（交易日收盘时点近似）")
        }
    }
    fun provider(raw: String): FundQuote {
        require(raw.length <= 128 * 1024) { "估值响应过大" }
        val json = Regex("^\\s*jsonpgz\\((\\{[\\s\\S]*})\\);?\\s*$").matchEntire(raw)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("估值服务未返回 JSONP 数据")
        val o = JSONObject(json)
        val code = t(o, "fundcode")?.takeIf { it.matches(Regex("[0-9]{6}")) } ?: throw IllegalArgumentException("估值基金代码无效")
        val nav = n(o, "dwjz")?.takeIf { it.signum() > 0 } ?: throw IllegalArgumentException("估值基准净值无效")
        val date = date(t(o, "jzrq")) ?: throw IllegalArgumentException("估值基准日期无效")
        val estimate = n(o, "gsz")?.takeIf { it.signum() > 0 } ?: throw IllegalArgumentException("暂无估值")
        val time = t(o, "gztime")?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}(:[0-9]{2})?")) && runCatching { LocalDateTime.parse(it.replace(' ', 'T')) }.isSuccess }
            ?: throw IllegalArgumentException("估值时间无效")
        return FundQuote(code, t(o, "name") ?: code, nav, date, estimate = estimate, estimateChange = n(o, "gszzl"), estimateTime = time)
    }

    fun encodeProfile(p: FundProfile): String = JSONObject().put("code", p.code).put("name", p.name).put("kind", p.kind.name)
        .put("currency", p.currency).put("foreign", p.foreign).put("disclosureDate", p.disclosureDate).put("allocationDate", p.allocationDate)
        .put("stockWeight", p.stockWeight?.toPlainString()).put("bondWeight", p.bondWeight?.toPlainString()).put("fundWeight", p.fundWeight?.toPlainString())
        .put("cashWeight", p.cashWeight?.toPlainString()).put("otherWeight", p.otherWeight?.toPlainString()).put("benchmark", p.benchmark).put("target", p.target).put("listed", p.listed)
        .put("annualYield", p.annualYield?.toPlainString()).put("typeName", p.typeName).put("bondProxy", p.bondProxy)
        .put("positions", JSONArray().also { a -> p.positions.forEach { a.put(JSONObject().put("symbol", it.symbol).put("weight", it.weight.toPlainString()).put("kind", it.kind.name)) } }).toString()
    fun decodeProfile(raw: String): FundProfile {
        val o = JSONObject(raw)
        val a = o.optJSONArray("positions") ?: JSONArray()
        require(a.length() <= 2000) { "持仓数量超限" }
        return FundProfile(o.getString("code"), o.getString("name"), FundKind.valueOf(o.getString("kind")), t(o, "currency") ?: "CNY", o.optBoolean("foreign"),
            date(t(o, "disclosureDate")), date(t(o, "allocationDate")), n(o, "stockWeight"), n(o, "bondWeight"), n(o, "fundWeight"), n(o, "cashWeight"), n(o, "otherWeight"),
            t(o, "benchmark"), t(o, "target"), t(o, "listed"), (0 until a.length()).map { i -> val row = a.getJSONObject(i); FundPosition(row.getString("symbol"), n(row, "weight") ?: BigDecimal.ZERO, PositionKind.valueOf(row.getString("kind"))) },
            n(o, "annualYield"), t(o, "typeName") ?: "", t(o, "bondProxy") ?: "sh511010")
    }
    fun encodeEstimate(e: FundEstimate): JSONObject = JSONObject().put("date", e.date).put("change", e.change.toPlainString()).put("basisPrice", e.basisPrice.toPlainString())
        .put("basisDate", e.basisDate).put("asOf", e.asOf).put("method", e.method.name).put("directCoverage", e.directCoverage.toPlainString())
        .put("modeledCoverage", e.modeledCoverage.toPlainString()).put("complete", e.complete).put("disclosureDate", e.disclosureDate).put("note", e.note)
        .put("currency", e.currency).put("referenceDate", e.referenceDate).put("confidence", e.confidence).put("source", e.source)
    fun decodeEstimate(o: JSONObject): FundEstimate? = runCatching {
        val date = date(t(o, "date")) ?: return null
        val price = n(o, "basisPrice")?.takeIf { it.signum() > 0 } ?: return null
        val change = n(o, "change")?.takeIf { it > BigDecimal("-100") } ?: return null
        val direct = n(o, "directCoverage")?.takeIf { it in BigDecimal.ZERO..BigDecimal("100") } ?: return null
        val modeled = n(o, "modeledCoverage")?.takeIf { it in BigDecimal.ZERO..BigDecimal("100") } ?: return null
        FundEstimate(date, change, price, date(t(o, "basisDate")), t(o, "asOf") ?: "", EstimateMethod.valueOf(o.getString("method")), direct, modeled,
            o.optBoolean("complete"), date(t(o, "disclosureDate")), (t(o, "note") ?: "").take(6000), t(o, "currency") ?: "CNY", date(t(o, "referenceDate")),
            t(o, "confidence") ?: "低", t(o, "source") ?: "本机公开数据模型")
    }.getOrNull()
}
