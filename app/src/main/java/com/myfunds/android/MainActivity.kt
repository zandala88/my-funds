package com.myfunds.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.myfunds.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var store: LocalStore
    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private var portfolio = Portfolio()
    private var quotes = emptyMap<String, FundQuote>()
    private var tab = 0
    private var group: String? = null
    private var status = "持仓与设置仅保存在本机"
    private var active = false
    private var generation = 0
    private var refreshing = false
    private var importing = false
    private var initialRequestDone = false
    private var holdingPage = 0
    private var pendingScreenshotRows: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val client = MarketClient()
    private val networkJobs = mutableListOf<Future<*>>()
    private val refreshLoop = object : Runnable {
        override fun run() {
            if (active && portfolio.settings.autoRefresh) {
                refreshFunds(); handler.postDelayed(this, 60000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingScreenshotRows = savedInstanceState?.getString("pendingScreenshotRows")
        store = LocalStore(this)
        try { portfolio = store.load(); quotes = store.loadQuotes() }
        catch (_: Exception) {
            AlertDialog.Builder(this).setTitle("本机数据读取失败").setMessage("原数据未覆盖。请保留备份，重新打开应用或恢复备份后再试。")
                .setPositiveButton("关闭") { _, _ -> finish() }.setCancelable(false).show(); return
        }
        group = savedInstanceState?.getString("selectedGroup")?.takeIf { id -> portfolio.groups.any { it.id == id } }
        setTheme(if (portfolio.settings.darkMode) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        render()
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            if (::root.isInitialized) root.setPadding(ui.dp(16), insets.systemWindowInsetTop + ui.dp(8), ui.dp(16), insets.systemWindowInsetBottom + ui.dp(6))
            insets
        }
        window.decorView.requestApplyInsets()
    }
    override fun onStart() {
        super.onStart(); active = true
        if (!::ui.isInitialized) return
        if (!initialRequestDone) { initialRequestDone = true; if (portfolio.holdings.isNotEmpty()) refreshFunds() }
        restartTimer()
    }
    override fun onSaveInstanceState(outState: Bundle) { pendingScreenshotRows?.let { outState.putString("pendingScreenshotRows", it) }; group?.let { outState.putString("selectedGroup", it) }; super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        val raw = pendingScreenshotRows ?: return
        pendingScreenshotRows = null
        safe {
            val fresh = store.load()
            val rows = ReviewedImport.decode(raw)
            val destination = group?.takeIf { id -> fresh.groups.any { it.id == id } } ?: "default"
            val next = ReviewedImport.apply(fresh, rows, destination)
            if (next == fresh) { portfolio = fresh; toast("勾选的基金已在自选中，无需重复导入；原恢复点保留"); render(); return@safe }
            val prepared = BackupCodec.prepare(BackupCodec.encode(next))
            store.replacePreparedBackup(prepared); portfolio = next; holdingPage = 0
            toast("已保存 ${rows.size} 条核对结果，导入前数据可在设置中恢复")
            render(); refreshFunds()
        }
    }
    override fun onStop() {
        active = false; generation++; refreshing = false; handler.removeCallbacks(refreshLoop)
        networkJobs.forEach { it.cancel(true) }; networkJobs.clear(); client.cancelAll()
        super.onStop()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); client.cancelAll(); executor.shutdownNow(); if (::store.isInitialized) store.close(); super.onDestroy() }
    private fun restartTimer() { handler.removeCallbacks(refreshLoop); if (active && portfolio.settings.autoRefresh) handler.postDelayed(refreshLoop, 60000) }
    private fun toast(message: String) = Toast.makeText(this, message.take(200), Toast.LENGTH_LONG).show()
    private fun safe(action: () -> Unit) { try { action() } catch (e: Exception) { toast(e.message ?: "操作未完成，原数据已保留") } }
    private fun commit(next: Portfolio) {
        check(!importing) { "正在恢复备份，请稍候" }
        store.save(next); portfolio = next; render()
    }
    private fun <T> network(task: () -> T, done: (T) -> Unit, failed: (String) -> Unit = { toast(it) }) {
        val ticket = generation
        networkJobs.removeAll { it.isDone }
        networkJobs.add(executor.submit {
            val result = runCatching(task)
            handler.post {
                if (!active || isDestroyed || isFinishing || ticket != generation) return@post
                result.fold({ value -> safe { done(value) } }, { error -> failed(error.message ?: "网络暂不可用，请稍后刷新") })
            }
        })
    }
    private fun <T> fileIo(task: () -> T, done: (T) -> Unit) {
        executor.submit {
            val result = runCatching(task)
            handler.post {
                if (isDestroyed || isFinishing) return@post
                result.fold({ safe { done(it) } }, { toast(it.message ?: "文件操作未完成，原数据已保留"); render() })
            }
        }
    }
    private fun render() {
        ui = Ui(this, portfolio.settings.darkMode)
        root = ui.box().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8)); setBackgroundColor(ui.background) }
        window.statusBarColor = ui.background; window.navigationBarColor = ui.background
        window.decorView.systemUiVisibility = if (portfolio.settings.darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root.addView(ui.text(if (tab == 0) "个人基金簿" else "本机与备份", 12f, ui.muted))
        root.addView(ui.box(false).apply {
            addView(ui.text(if (tab == 0) "我的自选" else "设置", 29f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (tab == 0) addView(ui.button(if (refreshing) "更新中" else "刷新") { refreshFunds() })
        })
        root.addView(ui.text(if (tab == 0) status else "数据存在本机，换机用文件恢复", 12f, ui.muted).apply { maxLines = 2 })
        body = ui.box().apply { setPadding(0, ui.dp(18), 0, ui.dp(10)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tab == 0) holdingsPage() else settingsPage()
        root.addView(ui.box(false).apply {
            ui.round(this, ui.card, 34); setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3))
            listOf("自选", "设置").forEachIndexed { i, label ->
                addView(ui.button(label, primary = i == tab) { tab = i; render() }, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
            }
        })
        setContentView(root); root.requestApplyInsets()
    }
    private fun holdingsPage() {
        val selected = portfolio.holdings.filter { group == null || it.groupId == group }
        val filtered = portfolio.copy(holdings = selected, trades = portfolio.trades.filter { t -> selected.any { it.id == t.holdingId } })
        val summary = PortfolioMath.summary(filtered, quotes.mapNotNull { (code, q) -> q.nav?.let { code to it } }.toMap())
        val daily = MarketMath.portfolioDailies(filtered, quotes, today())
        val dayTotal = if (daily.any { it == null }) null else daily.filterNotNull().fold(BigDecimal.ZERO) { a, d -> a + d.amount }
        body.addView(ui.panel().apply {
            ui.round(this, Color.parseColor(if (ui.dark) "#294638" else "#E4EEE5"))
            addView(ui.text("持仓总额（按已公布净值）", 12f, ui.muted))
            addView(ui.text(ui.amount(summary.marketValue), 39f, bold = true))
            addView(ui.box(false).apply {
                ui.stat(this, "持有收益", ui.signed(summary.profit), ui.changeColor(summary.profit))
                ui.stat(this, "已实现 · 建账以来", ui.signed(summary.realized), ui.changeColor(summary.realized))
            })
            if (summary.missing > 0) addView(ui.text("${summary.missing} 只持仓暂无净值，汇总暂不完整", 11f, ui.muted))
            addView(ui.text("收益合计  ${ui.signed(summary.totalProfit)}", 12f, ui.changeColor(summary.totalProfit)))
            addView(ui.text("当日收益${if (daily.any { it?.estimated == true }) "（含估算）" else "（按公布涨幅）"}  ${ui.signed(dayTotal)}", 12f, ui.changeColor(dayTotal)))
        })
        body.addView(ui.box(false).apply {
            addView(ui.button("＋ 添加基金", primary = true) { searchDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("截图导入") { startActivityForResult(Intent(this@MainActivity, OcrImportActivity::class.java), 102) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val groupName = group?.let { id -> portfolio.groups.firstOrNull { it.id == id }?.name } ?: "全部基金"
        body.addView(ui.box(false).apply {
            addView(ui.button("$groupName · ${selected.size} ▾") { chooseGroup() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("排序 ▾") { sortDialog() })
        })
        val sorted = PortfolioMath.sorted(portfolio.copy(holdings = selected), quotes, today())
        holdingPage = holdingPage.coerceIn(0, ((sorted.size - 1).coerceAtLeast(0) / 50))
        if (sorted.isEmpty()) body.addView(ui.centered("添加你关注的基金\n份额和成本可以稍后填写"))
        sorted.drop(holdingPage * 50).take(50).forEach { h ->
            val q = quotes[h.code]; val profit = q?.nav?.let { (it - h.cost) * h.shares }
            body.addView(ui.panel().apply {
                addView(ui.text((if (h.starred) "★ " else "") + h.name, 16f, bold = true))
                addView(ui.text("${h.code}  ·  ${q?.date ?: "暂无净值日期"}", 11f, ui.muted))
                addView(ui.box(false).apply {
                    ui.stat(this, "单位净值", ui.amount(q?.nav, 4))
                    ui.stat(this, "持有收益", if (h.shares.signum() == 0) "未录入" else ui.signed(profit), ui.changeColor(profit))
                    ui.stat(this, "盘中估算涨幅", ui.signed(currentEstimate(q)?.estimateChange, "%"), ui.changeColor(currentEstimate(q)?.estimateChange))
                })
                val metadata = PortfolioMath.metadata(h, portfolio.settings).joinToString("  ·  ") { (label, value) -> "$label ${value.stripTrailingZeros().toPlainString()}" }
                if (metadata.isNotEmpty()) addView(ui.text(metadata, 11f, ui.muted))
                val rate = PortfolioMath.holdingRate(h, q?.nav)
                addView(ui.text("持有收益率 ${ui.signed(rate, "%")}", 11f, ui.changeColor(rate)))
                currentEstimate(q)?.estimateTime?.let { addView(ui.text("估算时间 $it", 10f, ui.muted)) }
                setOnClickListener { fundDetail(h) }; setOnLongClickListener { holdingActions(h); true }
                contentDescription = "${h.name}，${h.code}。点击看详情，长按管理持仓"
            })
        }
        if (sorted.size > 50) body.addView(ui.box(false).apply {
            addView(ui.button("上一页") { holdingPage = (holdingPage - 1).coerceAtLeast(0); render() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text("${holdingPage + 1} / ${(sorted.size + 49) / 50}", 13f, ui.muted))
            addView(ui.button("下一页") { holdingPage++; render() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        body.addView(ui.text("长按基金管理持仓 · 估值为参考，实际收益以公布净值为准", 11f, ui.muted))
    }
    private fun currentEstimate(q: FundQuote?): FundQuote? = MarketMath.usableEstimate(q, today())
    private fun refreshFunds() {
        if (refreshing) return
        if (portfolio.holdings.isEmpty()) { toast("先添加一只基金"); return }
        refreshing = true; status = "正在更新基金数据…"; if (tab == 0) render()
        val codes = portfolio.holdings.map { it.code }
        network({ client.quotes(codes) }, { rows ->
            refreshing = false; store.saveQuotes(rows); quotes = store.loadQuotes()
            val valid = rows.count { it.nav != null }
            status = if (valid > 0) "已获取 $valid 只基金净值 · 以每只基金日期为准" else "暂无新净值，继续显示本机缓存"
            if (tab == 0) render()
        }, { error -> refreshing = false; status = "$error · 显示本机缓存"; if (tab == 0) render() })
    }
    private fun sortDialog() {
        val values = listOf("manual", "name", "profit", "change")
        AlertDialog.Builder(this).setTitle("排序方式").setItems(arrayOf("自定义 / 关注优先", "基金名称", "持有收益", "涨跌幅")) { _, i -> safe { commit(portfolio.copy(settings = portfolio.settings.copy(sort = values[i]))) } }.show()
    }
    private fun chooseGroup() {
        val labels = listOf("全部基金") + portfolio.groups.map { it.name } + "管理分组"
        AlertDialog.Builder(this).setTitle("选择分组").setItems(labels.toTypedArray()) { _, i ->
            if (i == labels.lastIndex) manageGroups() else { group = if (i == 0) null else portfolio.groups[i - 1].id; render() }
        }.show()
    }
    private fun manageGroups() {
        AlertDialog.Builder(this).setTitle("管理分组").setItems((listOf("＋ 新建分组") + portfolio.groups.filter { it.id != "default" }.map { "删除 ${it.name}（基金移回默认组）" }).toTypedArray()) { _, i ->
            if (i == 0) {
                val name = ui.field("分组名称")
                form("新建分组", listOf(name)) { require(name.text.isNotBlank()); commit(portfolio.copy(groups = portfolio.groups + Group(UUID.randomUUID().toString(), name.text.toString().trim()))) }
            } else safe {
                val id = portfolio.groups.filter { it.id != "default" }[i - 1].id
                if (group == id) group = null
                commit(portfolio.copy(groups = portfolio.groups.filter { it.id != id }, holdings = portfolio.holdings.map { if (it.groupId == id) it.copy(groupId = "default") else it }))
            }
        }.show()
    }
    private fun searchDialog() {
        val request = LatestRequest()
        val box = ui.box().apply { setPadding(ui.dp(18), 0, ui.dp(18), 0) }
        val query = ui.field("基金名称或六位代码"); val results = ui.box()
        box.addView(query); box.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, ui.dp(300)))
        val dialog = AlertDialog.Builder(this).setTitle("添加自选基金").setView(box).setPositiveButton("搜索", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val value = query.text.toString().trim(); if (value.isBlank()) return@setOnClickListener
            val token = request.next()
            results.removeAllViews(); results.addView(ui.text("正在搜索…", color = ui.muted))
            network({ client.search(value) }, { rows ->
                if (!dialog.isShowing || !request.isCurrent(token)) return@network
                results.removeAllViews()
                if (rows.isEmpty()) results.addView(ui.text("没有找到基金"))
                rows.take(30).forEach { row -> results.addView(ui.button("${row.name}\n${row.code}") {
                    safe {
                        require(portfolio.holdings.none { it.code == row.code }) { "该基金已在自选中" }
                        val h = Holding(UUID.randomUUID().toString(), row.code, row.name, group ?: "default", order = portfolio.holdings.size, confirmedDate = today(), initialShares = BigDecimal.ZERO)
                        commit(portfolio.copy(holdings = portfolio.holdings + h)); dialog.dismiss(); editHolding(h); refreshFunds()
                    }
                }) }
            }, { message -> if (dialog.isShowing && request.isCurrent(token)) { results.removeAllViews(); results.addView(ui.text(message)) } })
        } }
        dialog.show()
    }
    private fun form(title: String, inputs: List<EditText>, note: String = "", action: () -> Unit) {
        val box = ui.box().apply { setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8)) }
        inputs.forEach { field -> box.addView(ui.text(field.hint?.toString() ?: "", 12f, ui.muted)); box.addView(field) }
        if (note.isNotEmpty()) box.addView(ui.text(note, 11f, ui.muted))
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { try { action(); dialog.dismiss() } catch (e: Exception) { toast(e.message ?: "输入无效") } } }
        dialog.show()
    }
    private fun decimal(field: EditText) = field.text.toString().trim().takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+)?")) }?.toBigDecimalOrNull() ?: throw IllegalArgumentException("请输入有效的非负数值")
    private fun editHolding(h: Holding) {
        val shares = ui.field("已确认持有份额", h.shares.toPlainString(), true); val cost = ui.field("平均单位成本", h.cost.toPlainString(), true)
        val initial = portfolio.trades.none { it.holdingId == h.id }
        val baseline = ui.field("这份持仓的确认日期 YYYY-MM-DD", h.confirmedDate ?: today())
        form("${h.name} · 持仓", if (initial) listOf(shares, cost, baseline) else listOf(shares, cost), "首次录入为起始快照，请填这份持仓已确认的日期；已有记录时修改会记为今天的持仓校准。") {
            val qty = decimal(shares); val price = decimal(cost)
            val next = if (portfolio.trades.any { it.holdingId == h.id }) PortfolioOperations.calibrate(portfolio, h.id, qty, price, today())
                else portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) it.copy(shares = qty, cost = price, confirmedDate = baseline.text.toString().trim(), initialShares = qty) else it })
            commit(next)
        }
    }
    private fun today() = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private fun transaction(h: Holding, kind: String) {
        val shares = ui.field(if (kind == "CALIBRATE") "校准后份额" else "已确认成交份额", numeric = true)
        val price = ui.field(if (kind == "CALIBRATE") "校准后平均成本" else "成交单位净值", numeric = true)
        val fee = ui.field("手续费（元）", "0", true); val date = ui.field("确认日期 YYYY-MM-DD", today())
        form("${when (kind) { "BUY" -> "加仓"; "SELL" -> "减仓"; else -> "持仓校准" }} · ${h.name}", if (kind == "CALIBRATE") listOf(shares, price, date) else listOf(shares, price, fee, date), "只记录已确认的交易，不会发起实际买卖。") {
            val qty = decimal(shares); val value = decimal(price); val day = date.text.toString().trim()
            commit(when (kind) {
                "BUY" -> PortfolioOperations.buy(portfolio, h.id, qty, value, decimal(fee), day)
                "SELL" -> PortfolioOperations.sell(portfolio, h.id, qty, value, decimal(fee), day)
                else -> PortfolioOperations.calibrate(portfolio, h.id, qty, value, day)
            })
        }
    }
    private fun holdingActions(h: Holding) {
        val labels = arrayOf("编辑持仓", "记录加仓", "记录减仓", "持仓校准 / 分红折算", if (h.starred) "取消特别关注" else "特别关注", "移动到分组", "移到最前", "删除基金及其操作记录")
        AlertDialog.Builder(this).setTitle(h.name).setItems(labels) { _, i -> safe {
            when (i) {
                0 -> editHolding(h); 1 -> transaction(h, "BUY"); 2 -> transaction(h, "SELL"); 3 -> transaction(h, "CALIBRATE")
                4 -> commit(portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) it.copy(starred = !it.starred) else it }))
                5 -> AlertDialog.Builder(this).setTitle("移动到分组").setItems(portfolio.groups.map { it.name }.toTypedArray()) { _, g -> safe { commit(portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) it.copy(groupId = portfolio.groups[g].id) else it })) } }.show()
                6 -> commit(portfolio.copy(holdings = (listOf(h) + portfolio.holdings.filter { it.id != h.id }).mapIndexed { n, item -> item.copy(order = n) }))
                7 -> AlertDialog.Builder(this).setTitle("删除 ${h.name}？").setMessage("同时删除该基金的本机操作记录。建议先导出备份。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> safe { commit(portfolio.copy(holdings = portfolio.holdings.filter { it.id != h.id }, trades = portfolio.trades.filter { it.holdingId != h.id })) } }.show()
            }
        } }.show()
    }
    private fun fundDetail(h: Holding) {
        val container = ui.box().apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(10)) }
        val q = quotes[h.code]
        container.addView(ui.text("${h.code}  ·  净值日期 ${q?.date ?: "暂无"}", 12f, ui.muted))
        container.addView(ui.text("单位净值 ${ui.amount(q?.nav, 4)}  ·  当日涨幅 ${ui.signed(q?.change, "%")}", 15f, bold = true))
        val estimate = currentEstimate(q)
        container.addView(ui.text(if (estimate == null) "暂无当日盘中估值" else "估算净值 ${ui.amount(estimate.estimate, 4)}  ·  ${estimate.estimateTime}", 12f, ui.muted))
        val dailyProfit = MarketMath.portfolioDaily(portfolio, h, q, today())
        if (dailyProfit?.estimated == true) container.addView(ui.text("当日估算收益 ${ui.signed(dailyProfit.amount)}（按期初份额，仅供参考）", 12f, ui.muted))
        if (dailyProfit != null && !dailyProfit.estimated) container.addView(ui.text("当日收益 ${ui.signed(dailyProfit.amount)}（按公布涨幅计算）", 12f, ui.muted))
        val display = ui.box()
        val chartRequest = LatestRequest()
        fun showChart(range: String, yield: Boolean = false, intraday: Boolean = false) {
            val token = chartRequest.next()
            val key = "chart:${h.code}:$range:$yield:$intraday"
            val cached = runCatching { store.cache(key)?.let { decodePoints(it) } }.getOrNull()
            display.removeAllViews(); display.addView(ui.text(if (cached.isNullOrEmpty()) "正在获取走势…" else "本机缓存，按图中日期查看", 11f, ui.muted))
            if (!cached.isNullOrEmpty()) display.addView(LineChartView(this, cached, ui), LinearLayout.LayoutParams(-1, ui.dp(240)))
            network({ if (intraday) client.estimateHistory(h.code) else client.history(h.code, range, yield) }, { points ->
                if (!chartRequest.isCurrent(token)) return@network
                if (points.isEmpty() && !cached.isNullOrEmpty()) { toast("暂无新走势，保留本机缓存"); return@network }
                display.removeAllViews()
                if (points.isEmpty()) display.addView(ui.centered("该时段暂无数据")) else {
                    store.putCache(key, encodePoints(points)); display.addView(ui.text(if (yield) "基金区间涨幅（%），与个人账户收益不同" else if (intraday) "盘中估值涨幅（%），仅供参考" else "历史单位净值 · 按住图表查看", 11f, ui.muted))
                    display.addView(LineChartView(this, points, ui), LinearLayout.LayoutParams(-1, ui.dp(240)))
                }
            }, { error -> if (chartRequest.isCurrent(token)) { if (cached.isNullOrEmpty()) { display.removeAllViews(); display.addView(ui.centered("$error\n暂时无法显示此项数据")) } else toast("$error，保留缓存走势") } })
        }
        container.addView(ui.box(false).apply {
            listOf("月" to "y", "季" to "3y", "年" to "n", "三年" to "3n", "五年" to "5n").forEach { (label, range) -> addView(ui.button(label) { showChart(range) }, LinearLayout.LayoutParams(0, -2, 1f)) }
        })
        container.addView(display)
        container.addView(ui.box(false).apply {
            addView(ui.button("区间收益") { showChart("n", yield = true) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("估值曲线") { showChart("y", intraday = true) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        container.addView(ui.box(false).apply {
            addView(ui.button("基金资料") { dataDialog("基金资料", { client.information(h.code) }, "info:${h.code}") }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("经理") { dataDialog("基金经理", { client.managers(h.code) }, "managers:${h.code}") }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("披露持仓") { dataDialog("披露持仓（非实时持仓）", { client.positions(h.code) }, "positions:${h.code}") }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val records = portfolio.trades.filter { it.holdingId == h.id }.sortedByDescending { it.date }
        if (records.isNotEmpty()) {
            container.addView(ui.text("操作记录 · 建账以来", 15f, bold = true))
            records.take(30).forEach { t -> container.addView(ui.text("${t.date}  ${when (t.kind) { "BUY" -> "加仓"; "SELL" -> "减仓"; else -> "校准" }}  ${t.shares.toPlainString()} 份\n单位价格 ${t.price.toPlainString()} · 手续费 ${ui.amount(t.fee)}" + if (t.kind == "SELL") " · 已实现 ${ui.signed(t.realized)}" else "", 12f, ui.muted)) }
            if (records.size > 30) container.addView(ui.text("显示最近 30 条，完整记录包含在导出备份中", 11f, ui.muted))
        }
        AlertDialog.Builder(this).setTitle(h.name).setView(ScrollView(this).apply { addView(container) }).setNegativeButton("关闭", null).setPositiveButton("管理持仓") { _, _ -> holdingActions(h) }.show()
        showChart("y")
    }
    private fun encodePoints(points: List<ChartPoint>) = JSONArray().also { a -> points.forEach { a.put(JSONArray().put(it.date).put(it.value.toPlainString())) } }.toString()
    private fun decodePoints(raw: String): List<ChartPoint> { val a = JSONArray(raw); return (0 until a.length()).map { ChartPoint(a.getJSONArray(it).getString(0), BigDecimal(a.getJSONArray(it).getString(1))) } }
    private fun dataDialog(title: String, task: () -> List<Pair<String, String>>, cacheKey: String? = null) {
        val content = ui.box().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(10)); addView(ui.text("正在获取…", color = ui.muted)) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(content) }).setPositiveButton("关闭", null).show()
        val cached = runCatching { cacheKey?.let { store.cache(it) }?.let { JSONObject(it) } }.getOrNull()
        fun cachedRows(): List<Pair<String, String>> {
            val rows = cached?.optJSONArray("rows") ?: return emptyList()
            return (0 until rows.length()).map { rows.getJSONArray(it).getString(0) to rows.getJSONArray(it).getString(1) }
        }
        fun draw(rows: List<Pair<String, String>>, note: String) {
            content.removeAllViews(); content.addView(ui.text(note, 11f, ui.muted))
            if (rows.isEmpty()) content.addView(ui.centered("数据源暂未提供此项资料"))
            rows.forEach { (label, value) -> content.addView(ui.text(label, 12f, ui.muted)); content.addView(ui.text(value, 14f)) }
        }
        val previous = runCatching { cachedRows() }.getOrDefault(emptyList())
        if (previous.isNotEmpty()) draw(previous, "本机缓存 · ${cached?.optString("savedAt") ?: ""}")
        network(task, { rows ->
            if (!dialog.isShowing) return@network
            if (rows.isEmpty() && previous.isNotEmpty()) { draw(previous, "暂无新资料，保留本机缓存 · ${cached?.optString("savedAt") ?: ""}"); return@network }
            val time = java.time.OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString()
            if (cacheKey != null && rows.isNotEmpty()) store.putCache(cacheKey, JSONObject().put("savedAt", time)
                .put("rows", JSONArray().also { a -> rows.forEach { a.put(JSONArray().put(it.first).put(it.second)) } }).toString())
            draw(rows, "获取时间 $time")
        }, { error -> if (dialog.isShowing) { if (previous.isNotEmpty()) draw(previous, "$error · 显示本机缓存 ${cached?.optString("savedAt") ?: ""}") else { content.removeAllViews(); content.addView(ui.centered(error)) } } })
    }
    private fun settingsPage() {
        body.addView(ui.panel().apply {
            addView(ui.text("显示与刷新", 18f, bold = true))
            fun toggle(label: String, selected: Boolean, change: (Boolean) -> Unit) {
                addView(Switch(this@MainActivity).apply { text = label; setTextColor(ui.ink); isChecked = selected; setPadding(0, ui.dp(12), 0, ui.dp(12)); setOnCheckedChangeListener { _, value -> safe { change(value) } } })
            }
            toggle("深色外观", portfolio.settings.darkMode) { commit(portfolio.copy(settings = portfolio.settings.copy(darkMode = it))); recreate() }
            toggle("前台每 60 秒刷新基金", portfolio.settings.autoRefresh) { commit(portfolio.copy(settings = portfolio.settings.copy(autoRefresh = it))); restartTimer() }
            toggle("显示持有份额", portfolio.settings.showShares) { commit(portfolio.copy(settings = portfolio.settings.copy(showShares = it))) }
            toggle("显示平均成本", portfolio.settings.showCost) { commit(portfolio.copy(settings = portfolio.settings.copy(showCost = it))) }
            addView(ui.text("退出到后台或锁屏即停止刷新。", 11f, ui.muted))
        })
        body.addView(ui.panel().apply {
            addView(ui.text("文件备份与换机", 18f, bold = true))
            addView(ui.text("一个文件保存自选、持仓、交易记录、分组、排序与设置。备份为明文，请保存到你选择的位置。", 12f, ui.muted))
            addView(ui.button("导出完整备份") { pickDocument(true) })
            addView(ui.button("导入备份 / 换机恢复") { pickDocument(false) })
            addView(ui.button("恢复至上次导入前") {
                AlertDialog.Builder(this@MainActivity).setTitle("恢复导入前的数据？").setMessage("将覆盖当前持仓和设置。可以先导出当前备份。")
                    .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ -> safe {
                        check(active && !isDestroyed && !importing); importing = true
                        try { check(store.restorePrevious()) { "还没有可恢复的导入前备份" }; portfolio = store.load() }
                        finally { importing = false }
                        group = null; holdingPage = 0; toast("已恢复导入前的数据"); recreate()
                    } }.show()
            })
        })
        body.addView(ui.panel().apply {
            addView(ui.text("本机数据", 18f, bold = true))
            addView(ui.text("${portfolio.holdings.size} 只基金 · ${portfolio.trades.size} 条操作记录\n行情来源：东方财富 / 天天基金\n无账号、广告、推送或后台常驻服务", 12f, ui.muted))
            addView(ui.button("清理基金缓存（保留持仓与备份）") { safe { store.clearCache(); quotes = emptyMap(); toast("基金缓存已清理，持仓数据保留"); render() } })
            addView(ui.text("基金自选 0.2.0 · 原生安卓", 11f, ui.muted))
            addView(ui.button("开源许可与识别模型") {
                safe {
                    val notice = assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                        "—— $name ——\n" + assets.open("licenses/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    AlertDialog.Builder(this@MainActivity).setTitle("开源许可与识别模型")
                        .setView(ScrollView(this@MainActivity).apply { setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)); addView(ui.text(notice, 12f)) })
                        .setPositiveButton("关闭", null).show()
                }
            })
        })
    }
    private fun pickDocument(export: Boolean) = safe {
        check(!importing) { "正在处理备份，请稍候" }
        val intent = Intent(if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = if (export) "application/json" else "*/*"
            if (export) putExtra(Intent.EXTRA_TITLE, "funds-backup-${today()}.json")
        }
        startActivityForResult(intent, if (export) 100 else 101)
    }
    @Deprecated("Platform activity result API supports the minimum Android version")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102 && resultCode == RESULT_OK) { pendingScreenshotRows = data?.getStringExtra("reviewedRows"); return }
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == 100) safe {
            val snapshot = BackupCodec.encode(portfolio)
            fileIo({ contentResolver.openOutputStream(uri, "wt")?.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) } ?: throw IllegalArgumentException("无法写入所选文件"); Unit }) { toast("备份已导出，可在新手机导入") }
        }
        if (requestCode == 101) fileIo({
            val raw = contentResolver.openInputStream(uri)?.use { decodeUtf8Strict(readBounded(it, BackupCodec.MAX_BYTES)) } ?: throw IllegalArgumentException("无法读取所选文件")
            BackupCodec.prepare(raw) to JSONObject(raw).getString("exportedAt")
        }) { (prepared, exportedAt) ->
            if (!active) return@fileIo
            val p = prepared.portfolio
            AlertDialog.Builder(this).setTitle("恢复此备份？").setMessage("导出时间：$exportedAt\n${p.holdings.size} 只基金、${p.trades.size} 条记录。\n将替换本机数据，替换前自动保存本机恢复点。")
                .setNegativeButton("取消", null).setPositiveButton("确认恢复") { _, _ -> safe {
                    check(active && !isDestroyed && !isFinishing && !importing); importing = true
                    try { store.replacePreparedBackup(prepared); portfolio = prepared.portfolio }
                    finally { importing = false }
                    group = null; holdingPage = 0
                    toast("备份恢复完成"); recreate()
                } }.show()
        }
    }
}
