package com.myfunds.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class FundKind(val label: String) {
    EQUITY("股票"), INDEX("指数"), ETF("场内 ETF"), HYBRID("混合"), BOND("债券"), CONVERTIBLE("可转债"),
    MONEY("货币"), FLOATING_MONEY("浮动净值货币"), QDII("QDII"), FOF("FOF"), COMMODITY("黄金/商品"), REIT("REIT"), UNKNOWN("未分类")
}
enum class PositionKind { SECURITY, FUND }
enum class EstimateMethod(val label: String) { HOLDINGS("持仓推算"), INDEX("指数代理"), BOND("债券代理"), MONEY("收益预测"), OVERSEAS("海外行情"), FOF("基金穿透"), PROXY("价格代理"), MARKET("场内价格") }
data class FundPosition(val symbol: String, val weight: BigDecimal, val kind: PositionKind = PositionKind.SECURITY)
data class FundProfile(
    val code: String, val name: String, val kind: FundKind,
    val currency: String = "CNY", val foreign: Boolean = false,
    val disclosureDate: String? = null, val allocationDate: String? = null,
    val stockWeight: BigDecimal? = null, val bondWeight: BigDecimal? = null,
    val fundWeight: BigDecimal? = null, val cashWeight: BigDecimal? = null, val otherWeight: BigDecimal? = null,
    val benchmark: String? = null, val target: String? = null, val listed: String? = null,
    val positions: List<FundPosition> = emptyList(), val annualYield: BigDecimal? = null,
    val typeName: String = "", val bondProxy: String = "sh511010"
)
data class SecurityQuote(
    val symbol: String, val name: String, val price: BigDecimal, val previousClose: BigDecimal,
    val date: String, val asOf: String, val currency: String = "CNY",
    val referenceDate: String? = null, val zone: String = "Asia/Shanghai", val source: String = "腾讯行情"
) {
    fun change(): BigDecimal = (price.divide(previousClose, 18, RoundingMode.HALF_UP) - BigDecimal.ONE).movePointRight(2)
}
data class FundEstimate(
    val date: String, val change: BigDecimal, val basisPrice: BigDecimal, val basisDate: String?,
    val asOf: String, val method: EstimateMethod, val directCoverage: BigDecimal, val modeledCoverage: BigDecimal,
    val complete: Boolean, val disclosureDate: String?, val note: String,
    val currency: String = "CNY", val referenceDate: String? = null,
    val confidence: String = "低", val source: String = "本机公开数据模型"
)
data class EstimationResult(val estimate: FundEstimate?, val reason: String = "")

object FundEstimation {
    private val hundred = BigDecimal("100")
    private val zero = BigDecimal.ZERO
    private fun age(date: String?, day: String): Long? = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.parse(day)) }.getOrNull()
    private data class Return(val rate: BigDecimal, val quote: SecurityQuote, val note: String = "")

    fun estimate(profile: FundProfile, quote: FundQuote?, securities: Map<String, SecurityQuote>,
        profiles: Map<String, FundProfile>, fundQuotes: Map<String, FundQuote>, day: String): EstimationResult =
        compute(profile, quote, securities, profiles, fundQuotes, day, emptySet())

    private fun compute(p: FundProfile, q: FundQuote?, securities: Map<String, SecurityQuote>,
        profiles: Map<String, FundProfile>, fundQuotes: Map<String, FundQuote>, day: String, path: Set<String>): EstimationResult {
        fun unavailable(reason: String) = EstimationResult(null, reason)
        if (p.code in path || path.size >= 4) return unavailable("FOF 持仓循环或穿透层级超过 4 层")
        if (runCatching { LocalDate.parse(day) }.isFailure) return unavailable("收益日期无效")
        val basis = q?.nav?.takeIf { it.signum() > 0 }
        if (p.kind in listOf(FundKind.MONEY, FundKind.FLOATING_MONEY)) {
            if (basis == null || age(q.date, day)?.let { it in 0..7 } != true) return unavailable("货币收益依据缺失或超过 7 天")
            val rate = if (p.kind == FundKind.MONEY) q.incomePerTenThousand?.takeIf { it.signum() >= 0 }?.movePointLeft(2)
                ?: p.annualYield?.takeIf { it.signum() >= 0 }?.divide(BigDecimal("365"), 18, RoundingMode.HALF_UP)
            else p.annualYield?.takeIf { it.signum() >= 0 }?.divide(BigDecimal("365"), 18, RoundingMode.HALF_UP)
            if (rate == null) return unavailable("缺少万份收益或七日年化收益")
            return EstimationResult(FundEstimate(day, rate, basis, q.date, "依据 ${q.date}", EstimateMethod.MONEY, zero, hundred,
                true, null, "按 ${q.date} 已公布收益作单日预测，非今日已结算收益；未预计收益变化", p.currency,
                source = "最近万份收益 / 七日年化预测"))
        }
        fun valid(s: SecurityQuote?, date: String): SecurityQuote? = s?.takeIf {
            it.price.signum() > 0 && it.previousClose.signum() > 0 && it.date == date &&
                age(it.date, day)?.let { days -> days in 0..(if (p.foreign) 7 else 0) } == true &&
                (it.referenceDate == null || (age(it.referenceDate, it.date)?.let { days -> days in 1..10 } == true))
        }
        fun convertedQuote(candidate: SecurityQuote?, date: String): Return? {
            val s = valid(candidate, date) ?: return null
            if (s.currency == p.currency) return Return(s.change(), s)
            val reference = s.referenceDate ?: return null
            fun factor(from: String, to: String): BigDecimal? {
                if (from == to) return BigDecimal.ONE
                val direct = valid(securities["FX:$from:$to:$date"], date)?.takeIf { it.referenceDate == reference }
                val inverse = valid(securities["FX:$to:$from:$date"], date)?.takeIf { it.referenceDate == reference }
                return if (direct != null) direct.price.divide(direct.previousClose, 18, RoundingMode.HALF_UP)
                    else inverse?.previousClose?.divide(inverse.price, 18, RoundingMode.HALF_UP)
            }
            val factor = factor(s.currency, p.currency) ?: if (s.currency != "CNY" && p.currency != "CNY") {
                val fromCny = factor(s.currency, "CNY") ?: return null
                val toCny = factor(p.currency, "CNY") ?: return null
                fromCny.divide(toCny, 18, RoundingMode.HALF_UP)
            } else return null
            val rate = ((BigDecimal.ONE + s.change().movePointLeft(2)) * factor - BigDecimal.ONE).movePointRight(2)
            return Return(rate, s, "${s.currency}→${p.currency} 同日期区间汇率复合；日线收盘时点存在近似")
        }
        fun converted(symbol: String?, date: String): Return? = convertedQuote(symbol?.let { securities[it] }, date)
        if (p.kind in listOf(FundKind.ETF, FundKind.REIT)) {
            val s = p.listed?.let { valid(securities[it], day) } ?: return unavailable("缺少当天场内价格与昨收")
            if (s.currency != p.currency) return unavailable("场内行情币种不匹配")
            return EstimationResult(FundEstimate(day, s.change(), s.previousClose, s.referenceDate, "${s.asOf} ${s.zone}", EstimateMethod.MARKET,
                hundred, hundred, true, null, "场内成交价相对昨收；不等于基金公布净值收益", p.currency, s.referenceDate,
                confidence = "行情", source = s.source))
        }
        if (basis == null || age(q.date, day)?.let { it in 0..14 } != true) return unavailable("缺少有效公布净值作为收益金额基准")
        if (p.kind == FundKind.UNKNOWN) return unavailable("未识别基金类型，等待第三方估值或基金分类")
        val disclosed = p.disclosureDate ?: p.allocationDate
        if (age(disclosed, day)?.let { it in 0..200 } != true) return unavailable("持仓/配置披露日期缺失、尚未发生或超过 200 天")
        if (p.disclosureDate != null && p.allocationDate != null && p.disclosureDate != p.allocationDate)
            return unavailable("持仓和资产配置披露期不一致")
        val weights = listOfNotNull(p.stockWeight, p.bondWeight, p.fundWeight, p.cashWeight, p.otherWeight) + p.positions.map { it.weight }
        if (weights.any { it.signum() < 0 || it > BigDecimal("300") }) return unavailable("披露权重无效")

        val childEstimates = p.positions.filter { it.kind == PositionKind.FUND }.associate { position ->
            position.symbol to profiles[position.symbol]?.let { child -> compute(child, fundQuotes[position.symbol], securities, profiles, fundQuotes, day, path + p.code).estimate }
        }
        val benchmark = p.benchmark?.let { securities[it] }
        val target = p.target?.let { securities[it] }
        val bondFactor = if (p.kind == FundKind.CONVERTIBLE) securities["sh000832"] else securities[p.bondProxy]
        val modelDay = when {
            benchmark?.date == day -> day
            target?.date == day -> day
            p.foreign && bondFactor?.date == day -> day
            p.foreign && benchmark != null && age(benchmark.date, day)?.let { it in 0..7 } == true -> benchmark.date
            p.foreign && target != null && age(target.date, day)?.let { it in 0..7 } == true -> target.date
            p.foreign && bondFactor != null && age(bondFactor.date, day)?.let { it in 0..7 } == true -> bondFactor.date
            p.foreign && p.kind == FundKind.FOF && childEstimates.values.any { it != null } -> childEstimates.values.mapNotNull { it?.date }.minOrNull() ?: day
            p.foreign -> p.positions.mapNotNull { securities[it.symbol]?.date }.filter { age(it, day)?.let { days -> days in 0..7 } == true }.minOrNull() ?: day
            else -> day
        }
        var sum = zero
        var directCoverage = zero
        var modeledCoverage = zero
        var complete = true
        val sources = mutableListOf<SecurityQuote>()
        val notes = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val references = mutableSetOf<String?>()
        var method = when (p.kind) {
            FundKind.INDEX -> EstimateMethod.INDEX
            FundKind.BOND, FundKind.CONVERTIBLE -> EstimateMethod.BOND
            FundKind.QDII -> EstimateMethod.OVERSEAS
            FundKind.FOF -> EstimateMethod.FOF
            FundKind.COMMODITY -> EstimateMethod.PROXY
            else -> EstimateMethod.HOLDINGS
        }
        fun add(weight: BigDecimal, result: Return?, direct: Boolean, description: String): Boolean {
            if (weight.signum() == 0) return true
            if (result == null) { complete = false; missing.add("缺少 $description 行情/币种区间"); return false }
            if (references.isNotEmpty() && result.quote.referenceDate !in references) {
                complete = false; missing.add("$description 收益区间不一致"); return false
            }
            references.add(result.quote.referenceDate)
            sum += weight * result.rate.movePointLeft(2)
            modeledCoverage += weight
            if (direct) directCoverage += weight
            sources.add(result.quote)
            if (result.note.isNotBlank()) notes.add(result.note)
            return true
        }
        val hasAllocation = listOf(p.stockWeight, p.bondWeight, p.fundWeight, p.cashWeight).any { it != null }
        val stockWeight = p.stockWeight ?: zero
        val bondWeight = p.bondWeight ?: zero
        val fundWeight = p.fundWeight ?: zero
        val cashWeight = p.cashWeight ?: zero
        val securityPositions = p.positions.filter { it.kind == PositionKind.SECURITY }
        val declaredStocks = securityPositions.fold(zero) { a, v -> a + v.weight }
        if (p.stockWeight != null && declaredStocks > stockWeight + BigDecimal("1")) return unavailable("重仓股权重大于披露股票仓位")
        val primaryCoversBond = p.kind == FundKind.INDEX && bondWeight.signum() > 0 && stockWeight.signum() == 0 && fundWeight.signum() == 0
        val primaryWeight = if (primaryCoversBond) p.bondWeight else if (p.stockWeight != null || p.fundWeight != null) stockWeight + fundWeight else null
        val primary = if (modelDay == day) converted(p.benchmark, modelDay) ?: converted(p.target, modelDay)
            else converted(p.benchmark, modelDay) ?: converted(p.target, modelDay)
        var primaryUsed = false
        if (p.kind in listOf(FundKind.INDEX, FundKind.QDII, FundKind.COMMODITY) && primary != null) {
            val weight = primaryWeight ?: hundred
            add(weight, primary, false, "跟踪标的")
            primaryUsed = true
            if (primary.quote.symbol == p.target) {
                notes.add("目标 ETF 价格代理，包含折溢价变化，不是基金实时参考净值")
                method = EstimateMethod.PROXY
            } else {
                notes.add("跟踪标的日涨跌代理，按披露仓位计算")
                if (primary.quote.symbol in listOf("usQQQ", "usSPY", "usXOP", "usXLE", "usGLD", "usUSO", "usVNQ", "usBND"))
                    notes.add("海外 ETF 价格代理，含跟踪误差与折溢价")
            }
            if (primaryWeight == null) { complete = false; notes.add("仓位未知，仅显示标的 100% 敞口情景") }
        }
        if (!primaryUsed) {
            var observedStocks = zero
            for (position in securityPositions) {
                val result = converted(position.symbol, modelDay)
                val aligned = result != null && (references.isEmpty() || result.quote.referenceDate in references)
                if (aligned && add(position.weight, result, true, position.symbol)) observedStocks += position.weight
                else if (!hasAllocation) { complete = false; missing.add("缺少 ${position.symbol} 当日行情") }
            }
            if (p.stockWeight != null) {
                val remaining = (stockWeight - observedStocks).max(zero)
                val stockProxy = converted(p.benchmark, modelDay) ?: converted("sh000300", modelDay)
                if (remaining.signum() > 0) {
                    add(remaining, stockProxy, false, "剩余股票仓位")
                    notes.add("未披露/缺行情股票仓位使用指数代理，未放大重仓股权重")
                }
            } else if (declaredStocks.signum() > 0) { complete = false; notes.add("股票总仓位未知，仅已披露持仓贡献") }
        }
        if (bondWeight.signum() > 0 && !(primaryUsed && primaryCoversBond)) {
            val symbol = if (p.kind == FundKind.CONVERTIBLE) "sh000832" else p.bondProxy
            add(bondWeight, converted(symbol, modelDay), false, "债券仓位")
            notes.add(if (p.kind == FundKind.CONVERTIBLE) "债券部分使用转债指数代理，未拆分普通债" else "债券 ETF 价格代理，含折溢价变化及期限/信用差异")
        }
        if (!primaryUsed || p.kind == FundKind.FOF) {
            var childWeight = zero
            val children = p.positions.filter { it.kind == PositionKind.FUND }
            for (position in children) {
                val childQ = fundQuotes[position.symbol]
                val childP = profiles[position.symbol]
                val child = childEstimates[position.symbol]
                val publishedCandidate = childQ?.takeIf { it.date == modelDay && it.kind !in listOf(FundKind.ETF, FundKind.REIT) }?.let {
                    if (it.kind == FundKind.MONEY) it.incomePerTenThousand?.movePointLeft(2)
                    else it.change?.takeIf { c -> c > -hundred }
                }
                val nativeReference = securities["sh000300"]?.takeIf { it.date == modelDay }?.referenceDate
                val expectedReference = nativeReference ?: q.date?.takeIf { age(it, modelDay)?.let { days -> days in 1..3 } == true }
                val publishedRate = publishedCandidate?.takeIf { childQ?.previousDate != null && childQ.previousDate == expectedReference }
                val providerRate = childQ?.takeIf { MarketMath.usableEstimate(it, modelDay) != null }?.let {
                    it.estimateChange ?: it.estimate!!.divide(it.nav!!, 18, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).movePointRight(2)
                }
                val rawRate = publishedRate ?: providerRate ?: child?.takeIf { it.date == modelDay && it.complete }?.change
                val childCurrency = childP?.currency ?: childQ?.currency ?: "CNY"
                if (rawRate == null) {
                    complete = false
                    missing.add(if (publishedCandidate != null && publishedRate == null) "子基金 ${position.symbol} 缺少匹配的前一估值日区间" else "子基金 ${position.symbol} 缺少同日期同币种完整收益")
                    continue
                }
                val reference = if (publishedRate != null) childQ?.previousDate else if (providerRate != null) childQ?.date else child?.referenceDate
                val sourceQuote = SecurityQuote(position.symbol, position.symbol, (BigDecimal.ONE + rawRate.movePointLeft(2)) * hundred, hundred,
                    modelDay, if (providerRate != null) childQ?.estimateTime ?: modelDay else if (publishedRate != null) "净值日期 $modelDay" else child?.asOf ?: modelDay,
                    childCurrency, reference, source = if (publishedRate != null || providerRate != null) "子基金已公布/第三方收益" else child?.source ?: "子基金模型")
                val convertedChild = convertedQuote(sourceQuote, modelDay)
                if (convertedChild == null) { complete = false; missing.add("子基金 ${position.symbol} 缺少匹配的币种/汇率区间"); continue }
                val rate = convertedChild.rate
                if (convertedChild.note.isNotBlank()) notes.add(convertedChild.note)
                if (references.isNotEmpty() && reference !in references) { complete = false; missing.add("子基金 ${position.symbol} 收益区间不一致"); continue }
                references.add(reference)
                sum += position.weight * rate.movePointLeft(2)
                childWeight += position.weight
                modeledCoverage += position.weight
                if (publishedRate != null || providerRate != null) directCoverage += position.weight
                else directCoverage += position.weight * (child?.directCoverage ?: zero).movePointLeft(2)
                sources.add(convertedChild.quote)
                if (child != null && publishedRate == null && providerRate == null) notes.add("子基金 ${position.symbol} 使用 ${child.method.label} 模型")
            }
            if (children.isNotEmpty()) notes.add("子基金按实际披露权重穿透，未将前十大放大至 100%")
            if (fundWeight > childWeight + BigDecimal("0.01")) { complete = false; missing.add("未披露/缺行情子基金仓位") }
        }
        modeledCoverage += cashWeight
        if (cashWeight.signum() > 0) notes.add("披露现金按零价格变化情景，利息未计")
        val declaredTotal = stockWeight + bondWeight + fundWeight + cashWeight
        if (!hasAllocation || declaredTotal < BigDecimal("99.99") || modeledCoverage < declaredTotal.min(hundred) - BigDecimal("0.01") || (p.otherWeight ?: zero).signum() > 0) {
            complete = false; notes.add("资产配置未完全覆盖，未知资产未按零收益补齐")
        }
        if (declaredTotal > BigDecimal("105")) notes.add("保留披露杠杆；融资成本未计")
        if (modeledCoverage.signum() == 0 || (sources.isEmpty() && sum.signum() == 0)) return unavailable((missing + "缺少可用的估算行情").distinct().joinToString("；"))
        if (q.date != modelDay) notes.add("金额按 ${q.date} 公布净值近似，非完整重估净值")
        notes.add("披露后调仓及当日费用未计")
        notes.addAll(missing)
        val asOf = sources.map { if (it.asOf.contains(Regex("[A-Za-z]+/"))) it.asOf else "${it.asOf} ${it.zone}" }.distinct().take(3).joinToString(" / ").ifEmpty { "收益日 $modelDay" }
        return EstimationResult(FundEstimate(modelDay, sum.setScale(12, RoundingMode.HALF_UP), basis, q.date, asOf, method,
            directCoverage.min(hundred).setScale(8, RoundingMode.HALF_UP), modeledCoverage.min(hundred).setScale(8, RoundingMode.HALF_UP), complete, disclosed, notes.distinct().joinToString("；"), p.currency,
            references.singleOrNull(), source = "${p.kind.label} · 本机公开数据模型"), missing.distinct().joinToString("；"))
    }
}
