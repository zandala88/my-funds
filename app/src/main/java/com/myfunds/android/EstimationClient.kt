package com.myfunds.android

import com.myfunds.core.*
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** All I/O is injectable: forecasts can be verified without a live upstream. */
internal class EstimationClient(
    private val fetch: (String) -> String,
    private val cacheRead: (String) -> String? = { null },
    private val cacheWrite: (String, String) -> Unit = { _, _ -> },
    private val now: () -> Instant = { Instant.now() },
    private val checkCancelled: () -> Unit = { if (Thread.currentThread().isInterrupted) throw CancellationException("刷新已取消") }
) {
    private fun check() { if (Thread.currentThread().isInterrupted) throw CancellationException("刷新已取消"); checkCancelled() }
    private fun <T> optional(block: () -> T): T? {
        check()
        return try { block() } catch (e: CancellationException) { throw e }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException("刷新已取消") }
        catch (_: Exception) { check(); null }
    }
    private fun <A, B> parallel(items: List<A>, task: (A) -> B): List<B> {
        if (items.isEmpty()) return emptyList()
        check()
        val pool = Executors.newFixedThreadPool(minOf(4, items.size))
        val futures = items.map { item -> pool.submit<B> { check(); task(item) } }
        try {
            return futures.map { f ->
                check()
                try { f.get() } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is CancellationException) throw cause
                    throw e
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException("刷新已取消") }
            }
        } finally { futures.forEach { it.cancel(true) }; pool.shutdownNow() }
    }
    private fun fund(path: String, values: Map<String, String>): String = "https://fundmobapi.eastmoney.com/$path?" +
        (mapOf("deviceid" to "Wap", "plat" to "Wap", "product" to "EFund", "version" to "2.0.0") + values)
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    private fun profile(q: FundQuote): FundProfile {
        val key = "estimate-profile-v1:${q.code}"
        val cached = optional {
            val raw = cacheRead(key) ?: return@optional null
            val o = JSONObject(raw)
            val age = Duration.between(Instant.parse(o.getString("savedAt")), now()).seconds
            if (age !in 0..(if (o.optBoolean("complete")) 86400L else 300L)) return@optional null
            EstimationParser.decodeProfile(o.getString("profile")).takeIf { it.code == q.code }
        }
        if (cached != null) return cached
        val base = optional { EstimationParser.basic(fetch(fund("FundMNewApi/FundMNNBasicInformation", mapOf("FCODE" to q.code)))) }
            ?.takeIf { it.code == q.code } ?: return FundProfile(q.code, q.name, q.kind.takeUnless { it == FundKind.UNKNOWN } ?: EstimationParser.kind(q.name, listed = EstimationParser.listedCandidate(q.code, q.name)),
                currency = q.currency, foreign = q.currency != "CNY" || q.name.contains("QDII", true) || q.name.contains("海外"),
                listed = if (q.kind in listOf(FundKind.ETF, FundKind.REIT) || EstimationParser.listedCandidate(q.code, q.name)) EstimationParser.symbol(q.code) else null)
        var result = base
        var complete = true
        if (base.kind !in listOf(FundKind.MONEY, FundKind.FLOATING_MONEY, FundKind.ETF, FundKind.REIT)) {
            val allocation = optional { EstimationParser.allocation(result, fetch(fund("FundMNewApi/FundMNAssetAllocationNew", mapOf("FCODE" to q.code)))) }
            if (allocation != null) result = allocation
            if (allocation?.allocationDate == null) complete = false
            val positions = optional { EstimationParser.positions(result, fetch(fund("FundMNewApi/FundMNInverstPosition", mapOf("FCODE" to q.code)))) }
            if (positions != null) result = positions
            if (positions?.disclosureDate == null) complete = false
        }
        if (result.kind in listOf(FundKind.MONEY, FundKind.FLOATING_MONEY) && result.annualYield == null) {
            val annual = optional { EstimationParser.basic(fetch(fund("FundMApi/FundBaseTypeInformation.ashx", mapOf("FCODE" to q.code)))).annualYield }
            result = result.copy(annualYield = annual)
        }
        if (result.kind == FundKind.FLOATING_MONEY && result.annualYield == null) complete = false
        optional { cacheWrite(key, JSONObject().put("savedAt", now().toString()).put("complete", complete).put("profile", EstimationParser.encodeProfile(result)).toString()) }
        return result
    }
    private fun typed(q: FundQuote, p: FundProfile?): FundQuote {
        val kind = p?.kind?.takeUnless { it == FundKind.UNKNOWN } ?: q.kind
        return if (kind == FundKind.MONEY) q.copy(kind = kind, nav = BigDecimal.ONE,
            incomePerTenThousand = q.incomePerTenThousand ?: q.nav?.takeIf { q.kind != FundKind.MONEY }, currency = p?.currency ?: q.currency)
        else q.copy(kind = kind, currency = p?.currency ?: q.currency)
    }
    private fun clockValid(q: FundQuote): Boolean = runCatching {
        java.time.LocalDateTime.parse(q.estimateTime!!.replace(' ', 'T')).atZone(ZoneId.of("Asia/Shanghai")).toInstant() <= now().plusSeconds(300)
    }.getOrDefault(false)
    private fun usableProvider(q: FundQuote, day: String): Boolean = q.kind !in listOf(FundKind.ETF, FundKind.REIT) && clockValid(q) && MarketMath.usableEstimate(q.copy(estimateVerified = null), day) != null
    private fun currentPublished(q: FundQuote, day: String): Boolean = q.kind !in listOf(FundKind.ETF, FundKind.REIT) && q.date == day &&
        (if (q.kind == FundKind.MONEY) q.incomePerTenThousand != null else q.change?.let { it > BigDecimal("-100") } == true)
    private fun provider(q: FundQuote, day: String, foreign: Boolean): FundQuote {
        if (foreign || q.kind in listOf(FundKind.ETF, FundKind.REIT, FundKind.MONEY)) return q.copy(estimate = null, estimateChange = null, estimateTime = null, estimateVerified = false)
        if (usableProvider(q, day)) return q.copy(estimateVerified = true)
        if (currentPublished(q, day) || usableProvider(q, day) || q.kind in listOf(FundKind.MONEY, FundKind.FLOATING_MONEY, FundKind.ETF, FundKind.REIT)) return q
        val backup = optional { EstimationParser.provider(fetch("https://fundgz.1234567.com.cn/js/${q.code}.js?rt=${now().toEpochMilli()}")) }
        return if (backup != null && backup.code == q.code && backup.date == q.date && backup.nav?.compareTo(q.nav) == 0 && usableProvider(backup, day))
            q.copy(estimate = backup.estimate, estimateChange = backup.estimateChange, estimateTime = backup.estimateTime, estimateVerified = true)
        else if (q.estimate != null) q.copy(estimate = null, estimateChange = null, estimateTime = null, estimateVerified = false) else q
    }
    private fun referenceDate(q: FundQuote): FundQuote {
        if (q.previousDate != null || q.kind in listOf(FundKind.ETF, FundKind.REIT)) return q
        val date = q.date ?: return q
        if (q.kind == FundKind.MONEY && q.incomePerTenThousand != null) return q.copy(previousDate = java.time.LocalDate.parse(date).minusDays(1).toString())
        val nav = q.nav ?: return q
        val change = q.change ?: return q
        val key = "estimate-reference-v1:${q.code}:$date:$nav:$change"
        val cached = optional { cacheRead(key)?.takeIf { value -> runCatching { java.time.LocalDate.parse(value) }.isSuccess && value < date } }
        if (cached != null) return q.copy(previousDate = cached)
        val points = optional { MarketParser.history(fetch(fund("FundMApi/FundNetDiagram.ashx", mapOf("FCODE" to q.code, "RANGE" to "m")))) } ?: return q
        val index = points.indexOfFirst { it.date == date && it.value.compareTo(nav) == 0 }
        if (index <= 0) return q
        val previous = points[index - 1]
        if (previous.value.signum() <= 0) return q
        val computed = (nav.divide(previous.value, 18, java.math.RoundingMode.HALF_UP) - BigDecimal.ONE).movePointRight(2)
        if ((computed - change).abs() > BigDecimal("0.02")) return q
        optional { cacheWrite(key, previous.date) }
        return q.copy(previousDate = previous.date)
    }
    private fun yahooSymbol(symbol: String): String? = when {
        symbol.startsWith("us") -> symbol.drop(2)
        symbol.startsWith("hk") -> when (val code = symbol.drop(2)) { "HSI" -> "^HSI"; "HSCEI" -> "^HSCE"; "HSTECH" -> "^HSTECH"; else -> code.trimStart('0').padStart(4, '0') + ".HK" }
        symbol.startsWith("yf:") -> symbol.drop(3)
        else -> null
    }
    private fun safeQuote(q: SecurityQuote, day: String): Boolean {
        if (q.date > day) return false
        val stamp = runCatching { LocalDateTime.parse(q.asOf.replace(' ', 'T')).atZone(ZoneId.of(q.zone)).toInstant() }.getOrNull()
        return stamp == null || stamp <= now().plusSeconds(300)
    }
    fun enrich(incoming: List<FundQuote>, day: String): List<FundQuote> {
        check()
        val quotes = incoming.map { q -> if (q.estimate != null && !clockValid(q)) q.copy(estimate = null, estimateChange = null, estimateTime = null, estimateVerified = false) else q }.associateBy { it.code }.toMutableMap()
        val profiles = mutableMapOf<String, FundProfile>()
        fun profilesFor(codes: List<String>) {
            val rows = codes.mapNotNull { quotes[it] }
            for (p in parallel(rows, ::profile)) profiles[p.code] = p
            for (q in rows) quotes[q.code] = typed(q, profiles[q.code])
        }
        profilesFor(quotes.keys.toList())
        // FOF expansion has a separate bounded dependency budget, with cycle detection in the engine.
        val requested = incoming.map { it.code }.toSet()
        for (depth in 0 until 3) {
            val children = profiles.values.flatMap { p -> p.positions.filter { it.kind == PositionKind.FUND }.map { it.symbol } }
                .distinct().filter { it !in profiles }.take((256 - (profiles.size - requested.size)).coerceAtLeast(0))
            if (children.isEmpty()) break
            val fetched = parallel(children.chunked(100)) { chunk -> optional {
                MarketParser.quotes(fetch(fund("FundMNewApi/FundMNFInfo", mapOf("Fcodes" to chunk.joinToString(","), "pageIndex" to "1", "pageSize" to "200"))))
            }.orEmpty() }.flatten()
            fetched.forEach { quotes[it.code] = it }
            profilesFor(fetched.map { it.code })
            if (fetched.isEmpty()) break
        }
        val provided = parallel(quotes.values.toList()) { provider(it, day, profiles[it.code]?.foreign == true) }
        provided.forEach { quotes[it.code] = it }
        val childCodes = profiles.values.flatMap { p -> p.positions.filter { it.kind == PositionKind.FUND }.map { it.symbol } }.distinct()
        parallel(childCodes.mapNotNull { quotes[it] }, ::referenceDate).forEach { quotes[it.code] = it }
        val needed = profiles.values.filter { p -> quotes[p.code]?.let { !currentPublished(it, day) && !usableProvider(it, day) } == true }
        val symbols = needed.flatMap { p ->
            listOfNotNull(p.benchmark, p.target, p.listed) + p.positions.filter { it.kind == PositionKind.SECURITY }.map { it.symbol } +
                (if ((p.stockWeight ?: BigDecimal.ZERO).signum() > 0 || p.kind == FundKind.FOF) listOf("sh000300") else emptyList()) +
                (if ((p.bondWeight ?: BigDecimal.ZERO).signum() > 0) listOf(if (p.kind == FundKind.CONVERTIBLE) "sh000832" else p.bondProxy) else emptyList())
        }.distinct()
        val securities = mutableMapOf<String, SecurityQuote>()
        val localSymbols = symbols.filter { it.matches(Regex("(sh|sz|hk|us)[A-Za-z0-9.]{1,16}")) }
        parallel(localSymbols.chunked(70)) { chunk -> optional { EstimationParser.tencent(fetch("https://qt.gtimg.cn/q=${chunk.joinToString(",")}")) }.orEmpty() }
            .flatten().filter { safeQuote(it, day) }.forEach { securities[it.symbol] = it }
        // Dated daily bars supply explicit previous valuation dates, needed for FX/FOF alignment.
        val overseas = symbols.mapNotNull { symbol -> yahooSymbol(symbol)?.let { symbol to it } }
        val histories = parallel(overseas) { (symbol, ticker) -> optional {
            EstimationParser.yahoo(fetch("https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(ticker, "UTF-8")}?range=1mo&interval=1d"), symbol,
                if (symbol.startsWith("hk")) "HKD" else "USD")
        }.orEmpty() }
        histories.flatten().filter { safeQuote(it, day) }.groupBy { it.symbol }.forEach { (symbol, rows) ->
            val latest = rows.maxByOrNull { it.date } ?: return@forEach
            val existing = securities[symbol]
            if (existing == null || latest.date >= existing.date) securities[symbol] = latest
        }
        if (localSymbols.any { it.startsWith("sh") || it.startsWith("sz") }) {
            val session = optional {
                EstimationParser.yahoo(fetch("https://query1.finance.yahoo.com/v8/finance/chart/000300.SS?range=1mo&interval=1d"), "sh000300", "CNY")
                    .lastOrNull { it.date == day && it.currency == "CNY" && safeQuote(it, day) }
            }
            val reference = session?.referenceDate
            if (reference != null) {
                securities.entries.toList().forEach { (symbol, quote) ->
                    if ((symbol.startsWith("sh") || symbol.startsWith("sz")) && quote.date == day && quote.referenceDate == null)
                        securities[symbol] = quote.copy(referenceDate = reference)
                }
            }
        }
        val currencies = (needed.map { it.currency } + securities.values.map { it.currency }).distinct()
        val pairs = currencies.filter { it != "CNY" }.map { it to "CNY" }
        parallel(pairs) { (from, to) -> optional {
            val ticker = if (from == "USD") "CNY=X" else "${from}${to}=X"
            EstimationParser.yahoo(fetch("https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(ticker, "UTF-8")}?range=1mo&interval=1d"), "FX:$from:$to", to)
        }.orEmpty() }.flatten().filter { safeQuote(it, day) }.forEach { securities[it.symbol] = it }
        check()
        return incoming.map { original ->
            val q = quotes[original.code] ?: original
            val p = profiles[original.code]
            if (currentPublished(q, day) || usableProvider(q, day)) q.copy(model = null, estimateReason = null)
            else {
                val result = p?.let { FundEstimation.estimate(it, q, securities, profiles, quotes, day) }
                    ?: EstimationResult(null, "基金分类暂不可用")
                q.copy(model = result.estimate, estimateReason = result.reason.takeIf { it.isNotBlank() } ?: if (result.estimate == null) "暂无可用估算数据" else null)
            }
        }
    }
}
