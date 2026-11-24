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
    private var scrollY = 0
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
        tab = savedInstanceState?.getInt("selectedTab") ?: 0
        scrollY = savedInstanceState?.getInt("scrollY") ?: 0
        pendingScreenshotRows = savedInstanceState?.getString("pendingScreenshotRows")
        store = LocalStore(this)
        try { portfolio = store.load(); quotes = store.loadQuotes() }
        catch (_: Exception) {
            Ui(this, false).dialog().setTitle("本机数据读取失败").setMessage("原数据未覆盖。请保留备份，重新打开应用或恢复备份后再试。")
                .setPositiveButton("关闭") { _, _ -> finish() }.setCancelable(false).show(); return
        }
        group = savedInstanceState?.getString("selectedGroup")?.takeIf { id -> portfolio.groups.any { it.id == id } }
        setTheme(if (portfolio.settings.darkMode) R.style.AppTheme_Dark else R.style.AppTheme)
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
        checkAndApplyDca()
        if (!initialRequestDone) { initialRequestDone = true; if (portfolio.holdings.isNotEmpty()) refreshFunds() }
        restartTimer()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingScreenshotRows?.let { outState.putString("pendingScreenshotRows", it) }
        group?.let { outState.putString("selectedGroup", it) }
        outState.putInt("selectedTab", tab)
        outState.putInt("scrollY", scrollY)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        checkAndApplyDca()
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
    private fun checkAndApplyDca() {
        val td = today()
        val updated = PortfolioOperations.applyDcaCatchUp(portfolio, td, quotes)
        if (updated != portfolio) {
            store.save(updated)
            portfolio = updated
        }
    }
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
        ui = Ui(this, portfolio.settings.darkMode, portfolio.settings.redUp)
        root = ui.box().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8)); setBackgroundColor(ui.background) }
        window.statusBarColor = ui.background; window.navigationBarColor = ui.background
        window.decorView.systemUiVisibility = if (portfolio.settings.darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root.addView(ui.text(if (tab == 0) "拾盈 · 个人基金簿" else "本机与备份", 12f, ui.muted))
        root.addView(ui.box(false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(if (tab == 0) "我的自选" else "设置", 29f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (tab == 0) {
                val refreshBtn = ui.iconButton(R.drawable.ic_refresh, "刷新") { refreshFunds() }
                if (refreshing) {
                    val anim = android.view.animation.RotateAnimation(0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                        duration = 800
                        repeatCount = android.view.animation.Animation.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                    refreshBtn.startAnimation(anim)
                }
                addView(refreshBtn)
            }
        })
        root.addView(ui.text(if (tab == 0) status else "数据存在本机，换机用文件恢复", 12f, ui.muted).apply { maxLines = 2 })
        body = ui.box().apply { setPadding(0, ui.dp(18), 0, ui.dp(10)) }
        val scroller = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tab == 0) holdingsPage() else settingsPage()
        root.addView(ui.box(false).apply {
            setPadding(0, ui.dp(3), 0, ui.dp(3))
            listOf("自选", "设置").forEachIndexed { i, label ->
                addView(ui.button(label, primary = i == tab) { tab = i; scrollY = 0; render() }, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
            }
        })
        setContentView(root); root.requestApplyInsets()
        val targetScrollY = scrollY
        if (targetScrollY > 0) {
            scroller.post { scroller.scrollTo(0, targetScrollY) }
        }
        scroller.viewTreeObserver.addOnScrollChangedListener {
            scrollY = scroller.scrollY
        }
    }
    private fun holdingsPage() {
        val selected = portfolio.holdings.filter { group == null || it.groupId == group }
        val filtered = portfolio.copy(holdings = selected, trades = portfolio.trades.filter { t -> selected.any { it.id == t.holdingId } })
        val summary = PortfolioMath.summary(filtered, quotes.mapNotNull { (code, q) -> q.nav?.let { code to it } }.toMap())
        val dcaBudget = DcaBudget.daily(portfolio.holdings, quotes)
        val dcaActiveCount = dcaBudget.plans

        // Bento Row 1: Top Asymmetric 2-Card Row
        body.addView(ui.box(false).apply {
            // Left Card: Total Asset & Profit Overview
            val leftCard = ui.bentoCard().apply {
                addView(ui.text("持仓总额（按公布净值）", 11f, ui.muted))
                addView(ui.text(ui.amount(summary.marketValue), 28f, ui.ink, bold = true))
                val profitRow = ui.box(false).apply {
                    addView(ui.text("持有收益 ", 11f, ui.muted))
                    addView(ui.text(ui.signed(summary.profit), 12f, ui.changeColor(summary.profit), bold = true))
                    addView(ui.text("  ·  已实现 ", 11f, ui.muted))
                    addView(ui.text(ui.signed(summary.realized), 12f, ui.changeColor(summary.realized), bold = true))
                }
                addView(profitRow)
                if (summary.missing > 0) {
                    addView(ui.text("${summary.missing} 只暂无净值，显示以成本为准", 10f, ui.muted))
                }
            }
            addView(leftCard, LinearLayout.LayoutParams(0, -2, 1.6f).apply { marginEnd = ui.dp(8) })

            // Right Card: DCA Automatic Status
            val rightCard = ui.bentoCard().apply {
                addView(ui.text("每日定投预计", 11f, ui.muted))
                addView(ui.text("¥ ${ui.amount(dcaBudget.total)}", 22f, ui.accent, bold = true).apply {
                    maxLines = 1
                    setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                })
                addView(ui.text("$dcaActiveCount 只 · 全部分组", 11f, ui.muted))
                if (dcaBudget.missingPrices > 0) addView(ui.text("已知 ¥${ui.amount(dcaBudget.knownAmount)}\n${dcaBudget.missingPrices} 只待净值", 10f, ui.muted))
                else addView(ui.text("按计划执行日计算", 10f, ui.muted))
            }
            addView(rightCard, LinearLayout.LayoutParams(0, -1, 1f))
        })

        // Bento Row 2: Status & Sync Pill Banner
        body.addView(ui.box().apply {
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            ui.round(this, ui.soft, 12, ui.border, 1)
            val syncText = if (dcaActiveCount > 0) "● 定投计划已同步至今日 (${today()}) · 本地自动记账" else "● 数据安全保存在本地 · 点击基金查看导入记录"
            addView(ui.text(syncText, 11f, ui.muted))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(10) }
        })

        // Bento Row 3: Action Buttons
        body.addView(ui.box(false).apply {
            addView(ui.button("＋ 添加自选", primary = true) { searchDialog() }, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = ui.dp(6) })
            addView(ui.button("截图导入") { startActivityForResult(Intent(this@MainActivity, OcrImportActivity::class.java), 102) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            val groupName = group?.let { id -> portfolio.groups.firstOrNull { it.id == id }?.name } ?: "全部基金"
            addView(ui.button("$groupName ▾") { chooseGroup() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            addView(ui.button("排序 ▾") { sortDialog() }, LinearLayout.LayoutParams(0, -2, 0.9f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) }
        })

        val sorted = PortfolioMath.sorted(portfolio.copy(holdings = selected), quotes, today())
        holdingPage = holdingPage.coerceIn(0, ((sorted.size - 1).coerceAtLeast(0) / 50))
        if (sorted.isEmpty()) body.addView(ui.centered("添加或截图导入你关注的基金\n随时随地掌握个人持仓与定投"))

        sorted.drop(holdingPage * 50).take(50).forEach { h ->
            val q = quotes[h.code]
            val profit = q?.nav?.let { (it - h.cost) * h.shares }
            val rate = PortfolioMath.holdingRate(h, q?.nav)
            val holdingValue = q?.nav?.let { it * h.shares } ?: (h.cost * h.shares)
            val hasDca = (h.dcaShares ?: BigDecimal.ZERO) > BigDecimal.ZERO || (h.dcaAmount ?: BigDecimal.ZERO) > BigDecimal.ZERO

            body.addView(ui.bentoCard(radius = 18).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }

                // Top row: Name + Code Badge & Date
                val topRow = ui.box(false).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val nameBox = ui.box(false).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        if (h.starred) addView(ui.text("★ ", 14f, Color.parseColor("#F59E0B"), bold = true))
                        addView(ui.text(h.name, 15f, ui.ink, bold = true).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(ui.badge(h.code, ui.accent).apply {
                            setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1))
                        })
                    }
                    addView(nameBox, LinearLayout.LayoutParams(0, -2, 1f))
                    val dateStr = q?.date ?: (h.confirmedDate ?: "暂无日期")
                    addView(ui.text(dateStr, 11f, ui.muted))
                }
                addView(topRow)

                // Middle: 3-column Bento Metric Block
                val metricBlock = ui.box(false).apply {
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    ui.round(this, ui.soft, 12, ui.border, 1)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) }

                    // Col 1: 持有份额
                    addView(ui.box().apply {
                        addView(ui.text("持有份额", 10f, ui.muted))
                        addView(ui.text(if (h.shares.signum() == 0) "0.00" else h.shares.stripTrailingZeros().toPlainString(), 13f, ui.ink, bold = true))
                    }, LinearLayout.LayoutParams(0, -2, 1f))

                    // Col 2: 成本净值
                    addView(ui.box().apply {
                        addView(ui.text("成本净值", 10f, ui.muted))
                        addView(ui.text(if (h.cost.signum() == 0) "0.0000" else ui.amount(h.cost, 4), 13f, ui.ink, bold = true))
                    }, LinearLayout.LayoutParams(0, -2, 1f))

                    // Col 3: 持仓总额
                    addView(ui.box().apply {
                        addView(ui.text("持仓总额", 10f, ui.muted))
                        addView(ui.text(ui.amount(holdingValue), 13f, ui.ink, bold = true))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                addView(metricBlock)

                // Bottom row: DCA status badge on left, Profit & Rate on right
                val bottomRow = ui.box(false).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    if (hasDca) {
                        val dcaText = if ((h.dcaShares ?: BigDecimal.ZERO) > BigDecimal.ZERO) "定投: 每日 ${h.dcaShares?.stripTrailingZeros()?.toPlainString() ?: ""} 份" else "定投: 每日 ${h.dcaAmount?.stripTrailingZeros()?.toPlainString() ?: ""} 元"
                        addView(ui.badge(dcaText, ui.accent).apply {
                            setOnClickListener { setupDcaDialog(h) }
                        })
                    } else {
                        addView(ui.badge("＋ 设为定投", ui.muted, ui.soft).apply {
                            setOnClickListener { setupDcaDialog(h) }
                        })
                    }
                    val profitView = ui.box(false).apply {
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        val profitColor = ui.changeColor(profit)
                        addView(ui.text("收益 ", 11f, ui.muted))
                        addView(ui.text("${ui.signed(profit)} (${ui.signed(rate, "%")})", 12f, profitColor, bold = true))
                    }
                    addView(profitView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                addView(bottomRow)

                setOnClickListener { showHoldingLedger(h) }
                setOnLongClickListener { holdingActions(h); true }
                contentDescription = "${h.name}，${h.code}。点击查看本地导入账目，长按管理持仓"
            })
        }

        if (sorted.size > 50) body.addView(ui.box(false).apply {
            addView(ui.button("上一页") { holdingPage = (holdingPage - 1).coerceAtLeast(0); render() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text("${holdingPage + 1} / ${(sorted.size + 49) / 50}", 13f, ui.muted))
            addView(ui.button("下一页") { holdingPage++; render() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        body.addView(ui.text("点击基金查看本地导入记账明细 · 长按管理持仓与定投", 11f, ui.muted))
    }
    private fun currentEstimate(q: FundQuote?): FundQuote? = MarketMath.usableEstimate(q, today())
    private fun refreshFunds() {
        if (refreshing) return
        if (portfolio.holdings.isEmpty()) { toast("先添加一只基金"); return }
        refreshing = true; status = "正在更新基金数据…"; if (tab == 0) render()
        val codes = portfolio.holdings.map { it.code }
        network({ client.quotes(codes) }, { rows ->
            refreshing = false; store.saveQuotes(rows); quotes = store.loadQuotes()
            checkAndApplyDca()
            val valid = rows.count { it.nav != null }
            status = if (valid > 0) "已获取 $valid 只基金净值 · 定投已自动同步" else "暂无新净值，继续显示本机缓存"
            if (tab == 0) render()
        }, { error -> refreshing = false; status = "$error · 显示本机缓存"; if (tab == 0) render() })
    }
    private fun sortDialog() {
        val values = listOf("manual", "name", "profit", "change")
        ui.dialog().setTitle("排序方式").setItems(arrayOf("自定义 / 关注优先", "基金名称", "持有收益", "涨跌幅")) { _, i -> safe { commit(portfolio.copy(settings = portfolio.settings.copy(sort = values[i]))) } }.show()
    }
    private fun chooseGroup() {
        val labels = listOf("全部基金") + portfolio.groups.map { it.name } + "管理分组"
        ui.dialog().setTitle("选择分组").setItems(labels.toTypedArray()) { _, i ->
            if (i == labels.lastIndex) manageGroups() else { group = if (i == 0) null else portfolio.groups[i - 1].id; render() }
        }.show()
    }
    private fun manageGroups() {
        ui.dialog().setTitle("管理分组").setItems((listOf("＋ 新建分组") + portfolio.groups.filter { it.id != "default" }.map { "删除 ${it.name}（基金移回默认组）" }).toTypedArray()) { _, i ->
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
        val dialog = ui.dialog().setTitle("添加自选基金").setView(box).setPositiveButton("搜索", null).setNegativeButton("取消", null).create()
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
        val dialog = ui.dialog().setTitle(title).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { try { action(); dialog.dismiss() } catch (e: Exception) { toast(e.message ?: "输入无效") } } }
        dialog.show()
    }
    private fun decimal(field: EditText) = field.text.toString().trim().takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+)?")) }?.toBigDecimalOrNull() ?: throw IllegalArgumentException("请输入有效的非负数值")
    private fun today() = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private fun prevTradingDay(): String {
        val t = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        return when (t.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> t.minusDays(3)
            java.time.DayOfWeek.SUNDAY -> t.minusDays(2)
            java.time.DayOfWeek.SATURDAY -> t.minusDays(1)
            else -> t.minusDays(1)
        }.toString()
    }
    private fun datePickerField(hint: String, initialValue: String): EditText {
        val field = ui.field(hint, initialValue)
        field.isFocusable = false
        field.isClickable = true
        field.setOnClickListener {
            val td = today(); val prev = prevTradingDay()
            val options = arrayOf("今天已结算 ($td)", "今天未结算 ($prev)", "从日历选择其他日期…")
            ui.dialog().setTitle("选择结算日期").setItems(options) { _, which ->
                when (which) {
                    0 -> field.setText(td)
                    1 -> field.setText(prev)
                    2 -> {
                        val parsed = runCatching { LocalDate.parse(field.text.toString().trim()) }.getOrNull() ?: LocalDate.now(ZoneId.of("Asia/Shanghai"))
                        val dlg = android.app.DatePickerDialog(ui.dialogContext, { _, y, m, d ->
                            field.setText(LocalDate.of(y, m + 1, d).toString())
                        }, parsed.year, parsed.monthValue - 1, parsed.dayOfMonth)
                        ui.prepareDialog(dlg)
                        dlg.datePicker.maxDate = System.currentTimeMillis()
                        dlg.show()
                    }
                }
            }.show()
        }
        return field
    }
    private fun editHolding(h: Holding) {
        val shares = ui.field("已确认持有份额", h.shares.toPlainString(), true); val cost = ui.field("平均单位成本", h.cost.toPlainString(), true)
        val initial = portfolio.trades.none { it.holdingId == h.id }
        val baseline = datePickerField("结算日期（点击切换选项）", h.confirmedDate ?: today())
        form("${h.name} · 持仓", if (initial) listOf(shares, cost, baseline) else listOf(shares, cost), "首次录入为起始快照，请选择结算日期；已有记录时修改会记为今天的持仓校准。") {
            val qty = decimal(shares); val price = decimal(cost)
            val next = if (portfolio.trades.any { it.holdingId == h.id }) PortfolioOperations.calibrate(portfolio, h.id, qty, price, today())
                else portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) it.copy(shares = qty, cost = price, confirmedDate = baseline.text.toString().trim(), initialShares = qty) else it })
            commit(next)
        }
    }
    private fun transaction(h: Holding, kind: String) {
        val shares = ui.field(if (kind == "CALIBRATE") "校准后份额" else "已确认成交份额", numeric = true)
        val price = ui.field(if (kind == "CALIBRATE") "校准后平均成本" else "成交单位净值", numeric = true)
        val fee = ui.field("手续费（元）", "0", true); val date = datePickerField("结算日期（点击切换选项）", today())
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
        val labels = arrayOf("查看本地账目", "每日定投设置", "编辑持仓", "记录加仓", "记录减仓", "持仓校准 / 分红折算", if (h.starred) "取消特别关注" else "特别关注", "移动到分组", "移到最前", "删除基金及其操作记录")
        ui.dialog().setTitle(h.name).setItems(labels) { _, i -> safe {
            when (i) {
                0 -> showHoldingLedger(h)
                1 -> setupDcaDialog(h)
                2 -> editHolding(h)
                3 -> transaction(h, "BUY")
                4 -> transaction(h, "SELL")
                5 -> transaction(h, "CALIBRATE")
                6 -> commit(portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) it.copy(starred = !it.starred) else it }))
                7 -> ui.dialog().setTitle("移动到分组").setItems(portfolio.groups.map { it.name }.toTypedArray()) { _, g -> safe { commit(portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) it.copy(groupId = portfolio.groups[g].id) else it })) } }.show()
                8 -> commit(portfolio.copy(holdings = (listOf(h) + portfolio.holdings.filter { it.id != h.id }).mapIndexed { n, item -> item.copy(order = n) }))
                9 -> ui.dialog().setTitle("删除 ${h.name}？").setMessage("同时删除该基金的本机操作记录。建议先导出备份。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> safe { commit(portfolio.copy(holdings = portfolio.holdings.filter { it.id != h.id }, trades = portfolio.trades.filter { it.holdingId != h.id })) } }.show()
            }
        } }.show()
    }
    private fun showHoldingLedger(h: Holding) {
        val q = quotes[h.code]
        val holdingValue = q?.nav?.let { it * h.shares } ?: (h.cost * h.shares)
        val profit = q?.nav?.let { (it - h.cost) * h.shares }
        val rate = PortfolioMath.holdingRate(h, q?.nav)
        val container = ui.box().apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(16)) }

        // Header: Code Badge & Benchmark Date
        val subRow = ui.box(false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.badge(h.code, ui.accent))
            addView(ui.text("  ·  导入/基准日期 ${h.confirmedDate ?: "未记录"}", 12f, ui.muted))
        }
        container.addView(subRow)

        // Section 1: Imported Holding Details Panel (Pure Local Ledger)
        container.addView(ui.bentoCard(radius = 16).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(12) }
            addView(ui.text("导入持仓记账 (纯本地存储)", 12f, ui.muted, bold = true))

            val grid1 = ui.box(false).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
                ui.stat(this, "持有份额", if (h.shares.signum() == 0) "0.00" else "${h.shares.stripTrailingZeros().toPlainString()} 份")
                ui.stat(this, "平均持仓成本", if (h.cost.signum() == 0) "0.0000" else "${ui.amount(h.cost, 4)} 元")
            }
            addView(grid1)

            val grid2 = ui.box(false).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
                ui.stat(this, "最新公布净值", "${ui.amount(q?.nav, 4)} 元", ui.ink)
                ui.stat(this, "持仓总市值", "${ui.amount(holdingValue)} 元", ui.ink)
            }
            addView(grid2)

            val grid3 = ui.box(false).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
                ui.stat(this, "持有盈亏金额", "${ui.signed(profit)} 元", ui.changeColor(profit))
                ui.stat(this, "持有收益率", ui.signed(rate, "%"), ui.changeColor(rate))
            }
            addView(grid3)

            q?.date?.let {
                addView(ui.text("注：最新净值公布日期为 $it", 10f, ui.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
            }
        })

        // Section 2: DCA Plan Box
        val hasDca = (h.dcaShares ?: BigDecimal.ZERO) > BigDecimal.ZERO || (h.dcaAmount ?: BigDecimal.ZERO) > BigDecimal.ZERO
        container.addView(ui.bentoCard(radius = 16).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) }
            val headerBox = ui.box(false).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("每日自动定投计划", 13f, ui.ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.badge(if (hasDca) "定投进行中" else "未开启", if (hasDca) ui.accent else ui.muted))
            }
            addView(headerBox)

            if (hasDca) {
                val planDesc = if ((h.dcaShares ?: BigDecimal.ZERO) > BigDecimal.ZERO) "每日定投 ${h.dcaShares?.stripTrailingZeros()?.toPlainString() ?: ""} 份" else "每日定投 ${h.dcaAmount?.stripTrailingZeros()?.toPlainString() ?: ""} 元 (按公布净值折算)"
                val ruleDesc = if (h.dcaWeekdaysOnly) "仅周一至周五执行" else "每天均自动执行"
                val lastDateDesc = "已自动执行并同步至: ${h.dcaLastDate ?: today()}"
                addView(ui.text("$planDesc · $ruleDesc\n$lastDateDesc", 12f, ui.ink).apply { setPadding(0, ui.dp(6), 0, ui.dp(8)) })
                val budget = DcaBudget.daily(listOf(h), quotes)
                addView(ui.text("单日预计投入 ¥${ui.amount(budget.total)}" + if (budget.missingPrices > 0) " · 等待净值" else if ((h.dcaShares ?: BigDecimal.ZERO) > BigDecimal.ZERO) " · 按最新净值估算" else "", 13f, ui.accent, true))
            } else {
                addView(ui.text("设置每日定投后，每天打开或刷新系统会自动按计划增加份额，并加权计算平均成本。", 11f, ui.muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(8)) })
            }

            val dcaBtn = ui.button(if (hasDca) "修改定投计划" else "开启每日定投") { setupDcaDialog(h) }
            addView(dcaBtn)
        })

        // Section 3: Operation & Trade Records
        val records = portfolio.trades.filter { it.holdingId == h.id }.sortedByDescending { it.date }
        container.addView(ui.text("本机操作流水记录 (${records.size})", 14f, ui.ink, bold = true))
        if (records.isEmpty()) {
            container.addView(ui.text("暂无追加操作流水，当前数据来自初始导入快照。", 12f, ui.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
        } else {
            records.take(50).forEach { t ->
                val kindDesc = when (t.kind) {
                    "DCA" -> "● 自动定投买入"
                    "BUY" -> "＋ 加仓买入"
                    "SELL" -> "－ 减仓卖出"
                    else -> "⚙ 持仓校准"
                }
                val kindColor = when (t.kind) {
                    "DCA" -> ui.accent
                    "BUY" -> ui.changeColor(BigDecimal.ONE)
                    "SELL" -> ui.changeColor(BigDecimal.ONE.negate())
                    else -> ui.muted
                }
                val line = "${t.date}  $kindDesc  ${t.shares.stripTrailingZeros().toPlainString()} 份\n单价 ${t.price.stripTrailingZeros().toPlainString()} 元 · 手续费 ${ui.amount(t.fee)} 元" + if (t.kind == "SELL") " · 实现盈亏 ${ui.signed(t.realized)}" else ""
                container.addView(ui.text(line, 12f, kindColor).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) })
            }
            if (records.size > 50) container.addView(ui.text("仅显示最新 50 条记录，完整数据保存在本地备份中。", 10f, ui.muted))
        }

        ui.dialog()
            .setTitle(h.name)
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("关闭", null)
            .setPositiveButton("管理持仓") { _, _ -> holdingActions(h) }
            .show()
    }
    private fun setupDcaDialog(h: Holding) {
        val box = ui.box().apply { setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(10)) }
        val sharesField = ui.field("每日定投份额 (份，如 10.00)", h.dcaShares?.stripTrailingZeros()?.toPlainString() ?: "", true)
        val amountField = ui.field("或 每日定投金额 (元，按最新净值折算)", h.dcaAmount?.stripTrailingZeros()?.toPlainString() ?: "", true)
        val weekdaySwitch = Switch(this).apply {
            text = "仅在交易日 (周一至周五) 执行"
            setTextColor(ui.ink)
            isChecked = h.dcaWeekdaysOnly
            setPadding(0, ui.dp(8), 0, ui.dp(8))
        }

        box.addView(ui.text("每日定投设置", 16f, ui.ink, bold = true))
        box.addView(ui.text("请设置每日固定定投的份额或金额（二选一）。系统每天会自动根据日期补齐定投并加权更新平均成本：", 12f, ui.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
        box.addView(sharesField)
        box.addView(amountField)
        box.addView(weekdaySwitch)

        val builder = ui.dialog()
            .setTitle("${h.name} · 定投")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并生效", null)

        val hasExistingDca = (h.dcaShares ?: BigDecimal.ZERO) > BigDecimal.ZERO || (h.dcaAmount ?: BigDecimal.ZERO) > BigDecimal.ZERO
        if (hasExistingDca) {
            builder.setNeutralButton("取消定投计划") { _, _ ->
                safe {
                    val cleared = h.copy(dcaShares = null, dcaAmount = null, dcaLastDate = null)
                    commit(portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) cleared else it }))
                    toast("已取消 ${h.name} 的定投计划")
                }
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val sVal = sharesField.text.toString().trim()
                    val aVal = amountField.text.toString().trim()
                    require(sVal.isNotBlank() || aVal.isNotBlank()) { "请输入每日定投的份额或金额" }

                    val shares = if (sVal.isNotBlank()) sVal.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: throw IllegalArgumentException("份额必须为大于0的数值") else null
                    val amount = if (aVal.isNotBlank()) aVal.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: throw IllegalArgumentException("金额必须为大于0的数值") else null

                    val updatedHolding = h.copy(
                        dcaShares = shares,
                        dcaAmount = if (shares != null) null else amount,
                        dcaWeekdaysOnly = weekdaySwitch.isChecked,
                        dcaLastDate = h.dcaLastDate ?: prevTradingDay()
                    )
                    commit(portfolio.copy(holdings = portfolio.holdings.map { if (it.id == h.id) updatedHolding else it }))
                    checkAndApplyDca()
                    render()
                    toast("每日定投计划已保存，并已自动累计至今日！")
                    dialog.dismiss()
                } catch (e: Exception) {
                    toast(e.message ?: "输入无效")
                }
            }
        }
        dialog.show()
    }
    private fun settingsPage() {
        body.addView(ui.panel().apply {
            addView(ui.text("显示与刷新", 18f, bold = true))
            fun toggle(label: String, selected: Boolean, change: (Boolean) -> Unit) {
                addView(Switch(this@MainActivity).apply { text = label; setTextColor(ui.ink); isChecked = selected; setPadding(0, ui.dp(12), 0, ui.dp(12)); setOnCheckedChangeListener { _, value -> safe { change(value) } } })
            }
            toggle("深色外观", portfolio.settings.darkMode) { enabled ->
                setTheme(if (enabled) R.style.AppTheme_Dark else R.style.AppTheme)
                commit(portfolio.copy(settings = portfolio.settings.copy(darkMode = enabled)))
            }
            toggle("前台每 60 秒刷新基金", portfolio.settings.autoRefresh) { commit(portfolio.copy(settings = portfolio.settings.copy(autoRefresh = it))); restartTimer() }
            toggle("显示持有份额", portfolio.settings.showShares) { commit(portfolio.copy(settings = portfolio.settings.copy(showShares = it))) }
            toggle("显示平均成本", portfolio.settings.showCost) { commit(portfolio.copy(settings = portfolio.settings.copy(showCost = it))) }
            addView(ui.button("涨跌配色：" + if (portfolio.settings.redUp) "红涨绿跌 ▾" else "绿涨红跌 ▾") {
                ui.dialog().setTitle("涨跌配色").setItems(arrayOf("红涨绿跌", "绿涨红跌")) { _, index ->
                    safe { commit(portfolio.copy(settings = portfolio.settings.copy(redUp = index == 0))) }
                }.setNegativeButton("取消", null).show()
            })
            addView(ui.text("退出到后台或锁屏即停止刷新。", 11f, ui.muted))
        })
        body.addView(ui.panel().apply {
            addView(ui.text("文件备份与换机", 18f, bold = true))
            addView(ui.text("一个文件保存自选、持仓、交易记录、分组、排序与设置。备份为明文，请保存到你选择的位置。", 12f, ui.muted))
            addView(ui.button("导出完整备份") { pickDocument(true) })
            addView(ui.button("导入备份 / 换机恢复") { pickDocument(false) })
            addView(ui.button("恢复至上次导入前") {
                ui.dialog().setTitle("恢复导入前的数据？").setMessage("将覆盖当前持仓和设置。可以先导出当前备份。")
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
            addView(ui.text("拾盈 0.4.0 · 原生安卓", 11f, ui.muted))
            addView(ui.button("开源许可与识别模型") {
                safe {
                    val notice = assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                        "—— $name ——\n" + assets.open("licenses/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    ui.dialog().setTitle("开源许可与识别模型")
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
            ui.dialog().setTitle("恢复此备份？").setMessage("导出时间：$exportedAt\n${p.holdings.size} 只基金、${p.trades.size} 条记录。\n将替换本机数据，替换前自动保存本机恢复点。")
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
