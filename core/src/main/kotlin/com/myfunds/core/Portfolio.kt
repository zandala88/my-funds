package com.myfunds.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class Holding(val id: String, val code: String, val name: String, val groupId: String = "default",
    val shares: BigDecimal = BigDecimal.ZERO, val cost: BigDecimal = BigDecimal.ZERO,
    val order: Int = 0, val starred: Boolean = false, val confirmedDate: String? = null, val initialShares: BigDecimal? = null,
    val dcaShares: BigDecimal? = null, val dcaAmount: BigDecimal? = null, val dcaLastDate: String? = null, val dcaWeekdaysOnly: Boolean = true, val dcaSchedule: DcaSchedule? = null)
data class Group(val id: String, val name: String)
data class Trade(val id: String, val holdingId: String, val kind: String, val shares: BigDecimal,
    val price: BigDecimal, val fee: BigDecimal, val date: String, val realized: BigDecimal = BigDecimal.ZERO)
enum class DisplaySize(val factor: Float) { SMALL(0.9f), STANDARD(1f), LARGE(1.15f) }

data class AppSettings(val darkMode: Boolean = false, val autoRefresh: Boolean = false,
    val sort: String = "manual", val showShares: Boolean = true, val showCost: Boolean = true,
    val indexCodes: List<String> = listOf("1.000001", "0.399001", "0.399006"), val redUp: Boolean = true, val displaySize: DisplaySize = DisplaySize.STANDARD)
data class Portfolio(val holdings: List<Holding> = emptyList(),
    val groups: List<Group> = listOf(Group("default", "默认分组")),
    val trades: List<Trade> = emptyList(), val settings: AppSettings = AppSettings(),
    val incomes: List<IncomeEntry> = emptyList(), val executions: List<DcaExecution> = emptyList()) {
    fun validate(): Portfolio {
        require(holdings.size <= 10000 && trades.size <= 100000 && groups.size in 1..1000) { "备份记录数量超过限制" }
        fun identity(ids: List<String>) { require(ids.all { it.isNotBlank() && it.length <= 100 } && ids.toSet().size == ids.size) { "记录标识为空或重复" } }
        identity(holdings.map { it.id }); identity(groups.map { it.id }); identity(trades.map { it.id })
        val groupIds = groups.map { it.id }.toSet(); val holdingIds = holdings.map { it.id }.toSet()
        require(groups.any { it.id == "default" }) { "缺少默认分组" }
        require(groups.all { it.name.isNotBlank() && it.name.length <= 100 }) { "分组名称无效" }
        holdings.forEach {
            require(it.code.matches(Regex("[0-9]{6}")) && it.name.isNotBlank() && it.name.length <= 200) { "基金名称或代码无效" }
            require(it.groupId in groupIds) { "基金引用了不存在的分组" }
            validDecimal(it.shares); validDecimal(it.cost)
            require((it.confirmedDate == null) == (it.initialShares == null)) { "初始份额与确认日期必须同时存在" }
            it.initialShares?.let { n -> validDecimal(n) }
            it.dcaShares?.let { n -> validDecimal(n) }
            it.dcaAmount?.let { n -> validDecimal(n) }
            it.dcaSchedule?.validate()
            it.dcaLastDate?.let { date -> require(runCatching { LocalDate.parse(date) }.isSuccess) { "定投日期无效" } }
            it.confirmedDate?.let { date -> require(runCatching { LocalDate.parse(date) }.isSuccess && date <= LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) { "初始日期无效或尚未发生" } }
        }
        require(holdings.map { it.code }.toSet().size == holdings.size) { "基金代码重复" }
        val latest = holdings.mapNotNull { h -> h.confirmedDate?.let { h.id to it } }.toMap().toMutableMap()
        trades.forEach {
            require(it.holdingId in holdingIds) { "交易引用了不存在的基金" }
            require(it.kind in listOf("BUY", "SELL", "CALIBRATE", "DCA")) { "未知的记录类型" }
            validDecimal(it.shares); validDecimal(it.price); validDecimal(it.fee); validDecimal(it.realized, true)
            require(it.kind == "CALIBRATE" || (it.shares > BigDecimal.ZERO && it.price > BigDecimal.ZERO)) { "交易份额和净值必须大于零" }
            require(it.kind != "SELL" || it.fee <= it.shares * it.price) { "手续费超过卖出金额" }
            require(it.kind == "SELL" || it.realized.signum() == 0) { "加仓和校准不能包含已实现收益" }
            require(it.kind != "CALIBRATE" || it.fee.signum() == 0) { "校准记录不能包含手续费" }
            require(runCatching { LocalDate.parse(it.date) }.isSuccess) { "操作日期无效" }
            require(it.date >= (latest[it.holdingId] ?: "")) { "同一基金的操作日期不能倒序" }
            latest[it.holdingId] = it.date
        }
        CalendarLedgerCodec.validate(incomes, executions)
        require(settings.sort in listOf("manual", "custom", "name", "profit", "change")) { "排序设置无效" }
        require(settings.indexCodes.size <= 12 && settings.indexCodes.distinct().size == settings.indexCodes.size &&
            settings.indexCodes.all { it.matches(Regex("[01]\\.[0-9]{6}")) }) { "指数设置无效" }
        return this
    }
}

fun validDecimal(n: BigDecimal, signed: Boolean = false) {
    require(n.precision() <= 32 && n.scale() in 0..18 && (signed || n >= BigDecimal.ZERO)) { "数值必须非负，且精度不能超过限制" }
}

object PortfolioOperations {
    fun buy(p: Portfolio, id: String, shares: BigDecimal, price: BigDecimal, fee: BigDecimal, date: String): Portfolio =
        trade(p, id, shares, price, fee, date, "BUY")
    fun sell(p: Portfolio, id: String, shares: BigDecimal, price: BigDecimal, fee: BigDecimal, date: String): Portfolio =
        trade(p, id, shares, price, fee, date, "SELL")
    fun calibrate(p: Portfolio, id: String, shares: BigDecimal, cost: BigDecimal, date: String): Portfolio =
        trade(p, id, shares, cost, BigDecimal.ZERO, date, "CALIBRATE")

    internal fun recordDca(p: Portfolio,id: String,qty: BigDecimal,price: BigDecimal,date: String,tradeId: String)=trade(p,id,qty,price,BigDecimal.ZERO,date,"DCA",tradeId)

    private fun trade(p: Portfolio, id: String, qty: BigDecimal, price: BigDecimal, fee: BigDecimal, date: String, kind: String, tradeId: String = UUID.randomUUID().toString()): Portfolio {
        p.validate(); validDecimal(qty); validDecimal(price); validDecimal(fee)
        require(runCatching { LocalDate.parse(date) }.isSuccess) { "操作日期无效" }
        require(kind == "CALIBRATE" || (qty > BigDecimal.ZERO && price > BigDecimal.ZERO)) { "份额与净值必须大于零" }
        val old = p.holdings.firstOrNull { it.id == id } ?: throw IllegalArgumentException("找不到该基金")
        val latestDate = p.trades.filter { it.holdingId == id }.maxOfOrNull { it.date } ?: old.confirmedDate ?: ""
        require(date >= latestDate) { "请按确认日期依次记录，不能早于最近操作或初始快照日期" }
        require(date <= LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) { "不能记录尚未发生的确认日期" }
        require(kind != "SELL" || qty <= old.shares) { "卖出份额超过持有份额" }
        require(kind != "SELL" || fee <= qty * price) { "手续费超过卖出金额" }
        val next = when (kind) {
            "BUY", "DCA" -> old.copy(shares = old.shares + qty, cost = (old.shares * old.cost + qty * price + fee).divide(old.shares + qty, 16, RoundingMode.HALF_UP))
            "SELL" -> old.copy(shares = old.shares - qty)
            else -> old.copy(shares = qty, cost = price)
        }
        val realized = if (kind == "SELL") (qty * (price - old.cost) - fee).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        return p.copy(holdings = p.holdings.map { if (it.id == id) next else it },
            trades = p.trades + Trade(tradeId, id, kind, qty, price, fee, date, realized)).validate()
    }

    fun applyDcaCatchUp(p: Portfolio, today: String, quotes: Map<String, FundQuote> = emptyMap(), history: List<DailyNav> = emptyList()): Portfolio {
        val dated = quotes.values.mapNotNull { q ->
            val nav=q.nav?.takeIf { it.signum()>0 } ?: return@mapNotNull null
            val date=q.date ?: return@mapNotNull null
            DailyNav(q.code,date,nav,currency=q.currency,kind=q.kind)
        }
        return DcaEngine.advance(p,today,history+dated,TradingCalendar.builtin()).portfolio
    }

}

data class PortfolioSummary(val marketValue: BigDecimal?, val profit: BigDecimal?, val realized: BigDecimal, val missing: Int) {
    val totalProfit: BigDecimal? get() = profit?.add(realized)
}
object PortfolioMath {
    fun holdingRate(h: Holding, nav: BigDecimal?): BigDecimal? = if (nav == null || h.cost.signum() <= 0 || h.shares.signum() <= 0) null
        else (nav - h.cost).multiply(BigDecimal("100")).divide(h.cost, 8, RoundingMode.HALF_UP)
    fun metadata(h: Holding, settings: AppSettings): List<Pair<String, BigDecimal>> = buildList {
        if (settings.showShares) add("持有份额" to h.shares)
        if (settings.showCost) add("平均成本" to h.cost)
    }
    fun sorted(p: Portfolio, quotes: Map<String, FundQuote>, today: String): List<Holding> = when (p.settings.sort) {
        "custom" -> p.holdings.sortedBy { it.order }
        "name" -> p.holdings.sortedBy { it.name }
        "profit" -> p.holdings.sortedWith(compareByDescending { h -> quotes[h.code]?.nav?.let { (it - h.cost) * h.shares } })
        "change" -> p.holdings.sortedWith(compareByDescending { MarketMath.usableEstimate(quotes[it.code], today)?.estimateChange ?: quotes[it.code]?.change })
        else -> p.holdings.sortedWith(compareByDescending<Holding> { it.starred }.thenBy { it.order })
    }
    fun summary(p: Portfolio, navs: Map<String, BigDecimal>): PortfolioSummary {
        val owned = p.holdings.filter { it.shares > BigDecimal.ZERO }
        val missing = owned.count { navs[it.code] == null }
        val value = if (missing == 0) owned.fold(BigDecimal.ZERO) { a, h -> a + h.shares * navs.getValue(h.code) } else null
        val basis = owned.fold(BigDecimal.ZERO) { a, h -> a + h.shares * h.cost }
        return PortfolioSummary(value, value?.subtract(basis), p.trades.fold(BigDecimal.ZERO) { a, t -> a + t.realized }, missing)
    }
}
