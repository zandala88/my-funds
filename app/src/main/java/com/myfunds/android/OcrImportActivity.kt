package com.myfunds.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.*
import com.myfunds.core.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Reviews candidates only. It never writes the portfolio database. */
class OcrImportActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var cards: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var defaultChosenDate: String = ""
    private lateinit var dateControls: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var selectAll: CheckBox
    private var updatingSelection = false
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val client = MarketClient()
    private var cancellation = AtomicBoolean(false)
    private var worker: Future<*>? = null
    private var generation = 0
    private var active = false
    private var rawText = ""
    private var pendingImages: List<Uri> = emptyList()
    private val rows = mutableListOf<Row>()
    private val matching = LatestRequest()
    private val searches = mutableListOf<Future<*>>()
    private data class Row(val selected: CheckBox, val name: EditText, val code: EditText, val shares: EditText,
        val cost: EditText, var selectedDate: String, val dateBtn: Button, val mode: Button, var modeIndex: Int, val reference: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = LocalStore(this).use { runCatching { it.load().settings }.getOrDefault(AppSettings()) }
        val dark = settings.darkMode
        setTheme(if (dark) R.style.AppTheme_Dark else R.style.AppTheme)
        ui = Ui(this, dark, settings.redUp, settings.displaySize)
        val root = ui.box().apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(8)); setBackgroundColor(ui.background) }
        root.addView(ui.box(false).apply {
            addView(ui.text("截图导入", 27f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("返回") { finish() })
        })
        root.addView(ui.text("支付宝持仓 · 本机识别 · 核对后再保存", 12f, ui.muted))
        root.addView(ui.box(false).apply {
            addView(ui.button("选择截图（可多选）", primary = true) { selectImage() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("修正识别文字") { editText() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(30)))
        status = ui.text("一次可选最多 12 张持仓或基金详情截图，逐张在本机识别。未显示的份额与成本需要手动补齐，也可只加入自选。", 12f, ui.muted)
        root.addView(status)
        cancelButton = ui.button("取消识别") { cancelRecognition(); status.text = "已取消，持仓未修改" }.apply { visibility = View.GONE }
        root.addView(cancelButton)
        defaultChosenDate = todayStr()
        dateControls = ui.box().apply {
            visibility = View.GONE
            addView(ui.text("结算日期选项 · 点击一键应用至全部条目", 12f, ui.muted))
            addView(ui.box(false).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(ui.button("今天已结算 (${todayStr().substring(5)})") { applyGlobalDate(todayStr()) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button("今天未结算 (${prevTradingDayStr().substring(5)})") { applyGlobalDate(prevTradingDayStr()) }, LinearLayout.LayoutParams(0, -2, 1f))

            })
        }
        root.addView(dateControls)
        selectAll = CheckBox(ui.context).apply {
            text = "全选"; setTextColor(ui.ink); buttonTintList = ColorStateList.valueOf(ui.accent)
            minHeight = ui.touchTarget; visibility = View.GONE
            setOnCheckedChangeListener { _, checked ->
                if (!updatingSelection) {
                    updatingSelection = true
                    try { rows.forEach { it.selected.isChecked = checked } }
                    finally { updatingSelection = false }
                    updateSelectionControl()
                }
            }
        }
        root.addView(selectAll)
        cards = ui.box().apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) }
        root.addView(ScrollView(this).apply { addView(cards) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(ui.button("核对完成，导入勾选项", primary = true) { confirm() })
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, inset -> root.setPadding(ui.dp(18), inset.systemWindowInsetTop + ui.dp(10), ui.dp(18), inset.systemWindowInsetBottom + ui.dp(8)); inset }
        root.requestApplyInsets()
        savedInstanceState?.getString("ocrReviewDraft")?.let { raw ->
            try {
                val draft = OcrDraftCodec.decode(raw); rawText = draft.text; defaultChosenDate = draft.defaultDate.ifBlank { todayStr() }
                renderDrafts(draft.rows); status.text = "已恢复未提交的核对内容，持仓尚未修改。"
            } catch (e: Exception) { status.text = e.message ?: "核对草稿无法恢复，原持仓未修改" }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::cards.isInitialized) runCatching { OcrDraftCodec.encode(OcrReviewDraft(rawText, defaultChosenDate, rows.map { r ->
            OcrDraftRow(r.name.text.toString(), r.code.text.toString(), r.shares.text.toString(), r.cost.text.toString(), r.selectedDate, r.modeIndex, r.selected.isChecked, r.reference)
        })) }.getOrNull()?.let { outState.putString("ocrReviewDraft", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); active = true }
    override fun onResume() { super.onResume(); if (pendingImages.isNotEmpty()) { val images = pendingImages; pendingImages = emptyList(); recognize(images) } }
    override fun onStop() { active = false; if (worker?.isDone == false) status.text = "识别已暂停，请重新选择截图"; cancelRecognition(); searches.forEach { it.cancel(true) }; searches.clear(); client.cancelAll(); super.onStop() }
    override fun onDestroy() { cancellation.set(true); worker?.cancel(true); executor.shutdownNow(); handler.removeCallbacksAndMessages(null); client.cancelAll(); super.onDestroy() }
    private fun toast(value: String) = Toast.makeText(this, value.take(250), Toast.LENGTH_LONG).show()
    private fun selectImage() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                startActivityForResult(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                    type = "image/*"
                    putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX,
                        minOf(OcrBatch.MAX_IMAGES, android.provider.MediaStore.getPickImagesMaxLimit()))
                }, 200)
                return
            } catch (_: android.content.ActivityNotFoundException) { /* Older OEM gallery fallback. */ }
        }
        val gallery = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(gallery, 200) }
        catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); addCategory(Intent.CATEGORY_OPENABLE)
                }, 200)
            } catch (_: android.content.ActivityNotFoundException) { toast("没有可用的相册或图片选择器") }
        }
    }
    @Deprecated("Compatible platform result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 200 || resultCode != RESULT_OK) return
        val clip = data?.clipData
        if (clip != null && clip.itemCount > OcrBatch.MAX_IMAGES) {
            toast("一次最多选择 ${OcrBatch.MAX_IMAGES} 张截图，请重新选择"); return
        }
        val images = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            else listOfNotNull(data?.data)
        pendingImages = images.distinct()
    }
    private fun cancelRecognition() {
        generation++; cancellation.set(true); worker?.cancel(true); worker = null
        if (::progress.isInitialized) progress.visibility = View.GONE
        if (::cancelButton.isInitialized) cancelButton.visibility = View.GONE
    }
    private fun todayStr(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private fun prevTradingDayStr(): String {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        return when (today.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> today.minusDays(3)
            java.time.DayOfWeek.SUNDAY -> today.minusDays(2)
            java.time.DayOfWeek.SATURDAY -> today.minusDays(1)
            else -> today.minusDays(1)
        }.toString()
    }
    private fun formatDisplayDate(date: String): String {
        val today = todayStr(); val prev = prevTradingDayStr()
        return when (date) {
            today -> "今天已结算 ($date)"
            prev -> "今天未结算 ($date)"
            else -> if (date.isNotBlank()) "结算日期: $date" else "选择结算日期 ▾"
        }
    }
    private fun applyGlobalDate(date: String) {
        defaultChosenDate = date
        rows.forEach { it.selectedDate = date; it.dateBtn.text = formatDisplayDate(date) }
        toast("已应用结算日期: $date")
    }
    private fun showRowDateDialog(row: Row) {
        val today = todayStr(); val prev = prevTradingDayStr()
        val options = mutableListOf("今天已结算 ($today)", "今天未结算 ($prev)")
        if (row.selectedDate.isNotBlank() && row.selectedDate !in listOf(today, prev)) {
            options.add("当前选择 (${row.selectedDate})")
        }
        options.add("从日历选择其他日期…")
        ui.dialog().setTitle("选择结算日期").setItems(options.toTypedArray()) { _, i ->
            when (i) {
                0 -> { row.selectedDate = today; row.dateBtn.text = formatDisplayDate(today) }
                1 -> { row.selectedDate = prev; row.dateBtn.text = formatDisplayDate(prev) }
                else -> {
                    if (options[i].startsWith("从日历选择")) {
                        val initial = runCatching { LocalDate.parse(row.selectedDate) }.getOrNull() ?: LocalDate.now(ZoneId.of("Asia/Shanghai"))
                        val dialog = android.app.DatePickerDialog(ui.dialogContext, { _, year, month, dayOfMonth ->
                            val picked = LocalDate.of(year, month + 1, dayOfMonth)
                            if (picked.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) { toast("结算日期不能晚于今天"); return@DatePickerDialog }
                            row.selectedDate = picked.toString()
                            row.dateBtn.text = formatDisplayDate(row.selectedDate)
                        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
                        ui.prepareDialog(dialog)
                        dialog.datePicker.maxDate = System.currentTimeMillis()
                        dialog.show()
                    }
                }
            }
        }.setNegativeButton("取消", null).show()
    }
    private data class BatchResult(val text: String, val candidates: List<BatchHoldingCandidate>, val failed: List<String>, val empty: List<Int>)
    private fun recognize(images: List<Uri>) {
        cancelRecognition(); rawText = ""; rows.clear(); cards.removeAllViews(); dateControls.visibility = View.GONE; updateSelectionControl()
        cancellation = AtomicBoolean(false); val cancelled = cancellation; val ticket = generation
        progress.visibility = View.VISIBLE; cancelButton.visibility = View.VISIBLE; status.text = "正在读取 ${images.size} 张截图，识别过程不联网…"
        worker = executor.submit {
            val result = runCatching {
                require(images.size in 1..OcrBatch.MAX_IMAGES)
                val failed = mutableListOf<String>(); val empty = mutableListOf<Int>()
                val texts = mutableListOf<String>()
                val candidates = images.mapIndexed { index, uri ->
                    if (cancelled.get() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    try {
                        val lines = OfflineOcr(applicationContext).recognize(uri, cancelled) { message -> handler.post {
                            if (active && !isDestroyed && ticket == generation) status.text = "第 ${index + 1}/${images.size} 张 · $message"
                        } }
                        texts.add(lines.joinToString("\n") { it.text })
                        HoldingsOcr.parse(lines).also { if (it.isEmpty()) empty.add(index + 1) }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException || cancelled.get() || Thread.currentThread().isInterrupted) throw e
                        failed.add("第 ${index + 1} 张：${e.message?.take(90) ?: "图片识别失败"}"); if (texts.size <= index) texts.add(""); emptyList()
                    }
                }
                val combinedText = texts.joinToString(OcrBatch.TEXT_SEPARATOR)
                // Do not silently truncate a batch's editable text and lose later screenshots.
                require(combinedText.length <= 20000) { "本批识别文字过多，请减少截图后分批导入" }
                BatchResult(combinedText, OcrBatch.merge(candidates), failed, empty)
            }
            handler.post {
                if (!active || isDestroyed || isFinishing || ticket != generation) return@post
                progress.visibility = View.GONE; cancelButton.visibility = View.GONE
                result.fold({ batch ->
                    rawText = batch.text; renderCandidates(batch.candidates)
                    val problems = buildList {
                        if (batch.failed.isNotEmpty()) add(batch.failed.joinToString("；"))
                        if (batch.empty.isNotEmpty()) add("第 ${batch.empty.joinToString("、")} 张未找到基金")
                    }
                    status.append("\n已处理 ${images.size} 张。" + problems.joinToString("；"))
                }, { error -> status.text = if (error is OutOfMemoryError) "图片过大，请裁短截图后重试" else error.message?.take(250) ?: "识别未完成，请换清晰截图重试" })
            }
        }
    }
    private fun field(label: String, value: String?, numeric: Boolean = false): EditText = ui.field(label, value.orEmpty(), numeric).apply {
        filters = arrayOf(InputFilter.LengthFilter(if (numeric) 64 else 200))
    }
    private fun renderCandidates(candidates: List<BatchHoldingCandidate>) {
        val drafts = candidates.map { batch ->
            val candidate = batch.candidate
            val reference = buildList {
                add("来自第 ${batch.screenshots.joinToString("、")} 张截图")
                if (batch.conflict) add("同一基金有不同截图数值，请只勾选要保留的一条。")
                if (candidate.amount != null || candidate.profit != null) add("截图参考：金额 ${ui.amount(candidate.amount)} · 收益 ${ui.signed(candidate.profit)}\n金额和收益不会自动换算成份额或成本。")
                if (candidate.issues.isNotEmpty()) add(candidate.issues.joinToString(" · "))
            }.joinToString("\n").take(500)
            OcrDraftRow(candidate.name, candidate.code.orEmpty(), candidate.shares?.toPlainString().orEmpty(), candidate.cost?.toPlainString().orEmpty(),
                candidate.confirmedDate ?: defaultChosenDate.ifBlank { todayStr() }, if (candidate.shares != null && candidate.cost != null) 1 else 0, false, reference)
        }
        renderDrafts(drafts)
        verifyNamesByCode()
    }
    /** Fetch public fund metadata by code; never send the screenshot or account fields. */
    private fun verifyNamesByCode() {
        val originals = rows.filter { it.code.text.toString().matches(Regex("[0-9]{6}")) }
            .map { Triple(it, it.code.text.toString(), it.name.text.toString()) }
        if (originals.isEmpty()) return
        val ticket = generation
        searches.removeAll { it.isDone }
        searches.add(executor.submit {
            val verified = runCatching { client.quotes(originals.map { it.second }.distinct()).associateBy { it.code } }.getOrNull() ?: return@submit
            handler.post {
                if (!active || isDestroyed || ticket != generation) return@post
                originals.forEach { (row, code, originalName) ->
                    val name = verified[code]?.name?.takeIf { it.isNotBlank() && it != code }
                    if (name != null && row in rows && row.code.text.toString() == code && row.name.text.toString() == originalName) row.name.setText(name)
                }
            }
        })
    }
    private fun renderDrafts(drafts: List<OcrDraftRow>) {
        rows.clear(); cards.removeAllViews()
        dateControls.visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
        status.text = if (drafts.isEmpty()) "尚未找到基金。可裁剪到基金区域重试，或修正识别文字后重新解析。" else "${drafts.size} 条候选。请核对基金类别、数值和结算日期，再勾选导入。名称将按基金代码联网核对，图片不会上传。"
        drafts.forEachIndexed { index, draft ->
            val panel = ui.panel()
            val selected = CheckBox(ui.context).apply { text = "导入第 ${index + 1} 条"; setTextColor(ui.ink); buttonTintList = ColorStateList.valueOf(ui.accent); minHeight = ui.touchTarget; isChecked = draft.selected }
            val name = field("基金全称（核对 A/C 类别）", draft.name)
            val code = field("六位基金代码", draft.code).apply { inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6)) }
            val shares = field("已确认份额", draft.shares, true)
            val cost = field("平均单位成本", draft.cost, true)
            val initialDate = draft.date.ifBlank { defaultChosenDate.ifBlank { todayStr() } }
            val modes = arrayOf("只加自选（不录份额）", "录入新持仓", "更新已有持仓（校准）")
            lateinit var rowRef: Row
            val mode = ui.button(modes[draft.mode] + " ▾") {
                ui.dialog().setTitle("导入方式").setItems(modes) { _, index ->
                    rowRef.modeIndex = index
                    rowRef.mode.text = modes[index] + " ▾"
                }.setNegativeButton("取消", null).show()
            }
            val dateBtn = ui.button(formatDisplayDate(initialDate)) { showRowDateDialog(rowRef) }
            val row = Row(selected, name, code, shares, cost, initialDate, dateBtn, mode, draft.mode, draft.reference)
            rowRef = row; rows.add(row)
            selected.setOnCheckedChangeListener { _, _ -> if (!updatingSelection) updateSelectionControl() }
            panel.addView(selected)
            if (draft.reference.isNotBlank()) panel.addView(ui.text(draft.reference, 12f, ui.muted))
            panel.addView(ui.text("基金名称", 12f, ui.muted)); panel.addView(name)
            panel.addView(ui.text("基金代码", 12f, ui.muted)); panel.addView(code)
            panel.addView(ui.button("搜索并匹配基金") { match(row) })
            panel.addView(ui.text("导入方式", 12f, ui.muted)); panel.addView(mode, LinearLayout.LayoutParams(-1, -2))
            panel.addView(ui.text("份额", 12f, ui.muted)); panel.addView(shares)
            panel.addView(ui.text("平均单位成本", 12f, ui.muted)); panel.addView(cost)
            panel.addView(ui.text("持仓结算日期（点击切换已结算/未结算）", 12f, ui.muted)); panel.addView(dateBtn)
            cards.addView(panel)
        }
        updateSelectionControl()
    }
    private fun updateSelectionControl() {
        val count = rows.count { it.selected.isChecked }
        updatingSelection = true
        try {
            selectAll.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            selectAll.text = "全选 · 已选 $count/${rows.size}"
            selectAll.isChecked = rows.isNotEmpty() && count == rows.size
        } finally { updatingSelection = false }
    }
    private fun editText() {
        val input = ui.field("识别文字").apply {
            setSingleLine(false)
            setText(rawText); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8; maxLines = 14; filters = arrayOf(InputFilter.LengthFilter(20000)); textSize = 14f
        }
        ui.dialog().setTitle("修正识别文字").setMessage("可以为遗漏值补上“持有份额”“平均成本”等标签。重新解析会重置当前核对表。")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("重新解析") { _, _ ->
                try { cancelRecognition(); rawText = input.text.toString(); renderCandidates(OcrBatch.parseText(rawText)) } catch (e: Exception) { toast(e.message ?: "文字解析失败") }
            }.show()
    }
    private fun match(row: Row) {
        val code = row.code.text.toString().trim()
        val query = if (code.matches(Regex("[0-9]{6}"))) code else row.name.text.toString().replace("…", "").replace("...", "").trim()
        if (query.isBlank()) { toast("请先填名称或代码"); return }
        val ticket = generation; val queryToken = matching.next(); status.text = "正在按名称或代码查询基金，截图不会上传…"
        searches.removeAll { it.isDone }
        searches.add(executor.submit {
            val result = runCatching { client.search(query) }
            handler.post {
                if (!active || isDestroyed || ticket != generation || !matching.isCurrent(queryToken)) return@post
                result.fold({ matches ->
                    status.text = "请选择准确的基金和 A/C 类别"
                    if (matches.isEmpty()) { toast("没有找到候选，可修改名称或手动填代码"); return@fold }
                    val list = matches.take(30)
                    ui.dialog().setTitle("核对基金").setItems(list.map { "${it.name}\n${it.code}" }.toTypedArray()) { _, i -> row.name.setText(list[i].name); row.code.setText(list[i].code) }.setNegativeButton("取消", null).show()
                }, { status.text = "匹配暂不可用，可手动填写代码"; toast(it.message ?: "查询失败") })
            }
        })
    }
    private fun decimal(input: EditText): BigDecimal? {
        val value = input.text.toString().trim()
        if (value.isBlank()) return null
        require(value.matches(Regex("[0-9]+(\\.[0-9]+)?"))) { "份额和成本必须是有效非负数值" }
        return value.toBigDecimal().also { validDecimal(it) }
    }
    private fun confirm() {
        try {
            val selected = rows.filter { it.selected.isChecked }.map { row ->
                val mode = ImportMode.values()[row.modeIndex]
                ReviewedHolding(row.code.text.toString().trim(), row.name.text.toString().trim(),
                    if (mode == ImportMode.WATCH) null else decimal(row.shares), if (mode == ImportMode.WATCH) null else decimal(row.cost), row.selectedDate.trim(), mode)
            }
            require(selected.isNotEmpty()) { "请先勾选已经核对的条目" }
            LocalStore(this).use { ReviewedImport.apply(it.load(), selected, "default") }
            val encoded = ReviewedImport.encode(selected)
            ui.dialog().setTitle("导入 ${selected.size} 条核对结果？").setMessage("只保存已勾选条目。已有持仓仅在明确选择校准时更新；保存前保留本机恢复点。")
                .setNegativeButton("继续核对", null).setPositiveButton("确认导入") { _, _ ->
                    if (active && !isDestroyed) { setResult(RESULT_OK, Intent().putExtra("reviewedRows", encoded)); finish() }
                }.show()
        } catch (e: Exception) { toast(e.message ?: "请检查基金代码、份额、成本和日期") }
    }
}
