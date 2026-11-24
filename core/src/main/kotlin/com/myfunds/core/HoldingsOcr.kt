package com.myfunds.core

import java.math.BigDecimal
import java.time.LocalDate

data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
data class HoldingCandidate(val name: String, val code: String?, val shares: BigDecimal?, val cost: BigDecimal?,
    val amount: BigDecimal?, val profit: BigDecimal?, val issues: List<String>, val confirmedDate: String? = null)

object HoldingsOcr {
    private val codePattern = Regex("(?<![A-Za-z0-9.,])[0-9]{6}(?![A-Za-z0-9.,])")
    private val codeLabel = Regex("(?:基金代码|代码)[:：\\s]*([0-9]{6})(?![0-9])")
    private val numberPattern = Regex("^[￥¥]?\\s*([+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)(?:\\s*[份元])?$")
    private val labels = linkedMapOf(
        "持有总份额" to "shares", "持有份额" to "shares", "确认份额" to "shares", "份额" to "shares",
        "平均成本" to "cost", "持仓成本价" to "cost", "单位成本" to "cost", "成本净值" to "cost", "成本价" to "cost",
        "持有金额" to "amount", "持仓金额" to "amount", "持仓市值" to "amount", "金额" to "amount",
        "持有收益" to "profit", "持仓收益" to "profit")
    private val metricPattern = Regex(labels.keys.joinToString("|") { if (it == "份额") "(?<![\u4e00-\u9fff])份额" else Regex.escape(it) })
    private val totalRow = Regex("^(?:(?:基金|持仓|账户)?(?:总资产|总金额|总市值|总收益)|资产合计|持仓合计|账户合计)")
    private val dateLabel = Regex("^(?:持仓确认日期|份额确认日期|确认日期|持仓日期)[:：\\s]*(.*)$")
    private val navDatePattern = Regex("(?:基金净值|单位净值|净值)[^()（）]*[（(]([0-9]{1,2}-[0-9]{1,2}|[0-9]{4}-[0-9]{1,2}-[0-9]{1,2})[）)]")
    private data class Draft(var name: String = "", var code: String? = null, var date: String? = null, val values: MutableMap<String, BigDecimal> = mutableMapOf())
    fun parse(text: String): List<HoldingCandidate> = parse(text.lines().also { require(it.size <= 3000) { "识别文字过多，请分批核对" } }.mapIndexed { i, s -> OcrLine(s, 0, i * 40, 1000, i * 40 + 30) })

    fun parse(lines: List<OcrLine>): List<HoldingCandidate> {
        require(lines.size <= 10000) { "截图文字过多，请分成较短截图" }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })) {
            val center = (line.top.toLong() + line.bottom) / 2
            val previous = rows.lastOrNull()
            val anchor = previous?.firstOrNull()
            if (anchor != null && kotlin.math.abs(center - (anchor.top.toLong() + anchor.bottom) / 2) <= maxOf(4, minOf(anchor.bottom - anchor.top, line.bottom - line.top) / 2)) previous.add(line)
            else rows.add(mutableListOf(line))
        }
        val result = mutableListOf<HoldingCandidate>(); var draft: Draft? = null; var pending: String? = null; var profitColumns = false
        fun finish() {
            val d = draft ?: return
            if (d.name.isBlank() && d.code == null) return
            val issues = buildList {
                if (d.code == null) add("请匹配基金代码")
                if (d.name.contains('…') || d.name.contains("...")) add("基金名称被截断，请核对完整名称及份额类别")
                if (d.values["shares"] == null) add("截图未提供有效份额")
                if (d.values["cost"] == null) add("截图未提供有效单位成本")
            }
            result.add(HoldingCandidate(d.name.ifBlank { "基金 ${d.code}" }, d.code, d.values["shares"], d.values["cost"], d.values["amount"], d.values["profit"], issues, d.date))
        }
        for (row in rows) {
            val text = normalize(row.sortedBy { it.left }.joinToString(" ") { it.text }).replace("持有人金额", "持有金额").take(2000)
            if (text.isBlank()) continue
            // Alipay lays three return labels above three separate values. Only the middle
            // value is holding profit; percentages and yesterday's profit are not amounts.
            if (profitColumns) {
                profitColumns = false
                val values = text.split(Regex("\\s+"))
                if (values.size == 3 && values.last().endsWith('%')) {
                    parseNumber(values[1])?.let { draft?.values?.set("profit", it) }
                    continue
                }
            }
            if (text.startsWith("昨日收益") && text.contains("持有收益") && text.contains("持有收益率")) {
                pending = null; profitColumns = true; continue
            }
            // Badges can themselves contain “指数基金”, but are metadata for the preceding
            // name. Read a six-digit code here without opening another fund candidate.
            val badgeStart = Regex("(?:中高|中低|高|中|低)风险|金选").find(text)?.range?.first
            val nameBeforeBadge = badgeStart?.let { cleanName(codePattern.replace(text.take(it), "")) }
            if (badgeStart != null && !isFundName(nameBeforeBadge.orEmpty())) {
                if (draft != null && draft!!.code == null) draft!!.code = codePattern.find(text)?.value
                pending = null; continue
            }
            if (Regex("^(?:预计|待确认金额|待确认份额|基金净值|单位净值|净值|收益明细|交易记录|我的定投)").containsMatchIn(text)) {
                navDatePattern.find(text)?.groupValues?.get(1)?.let { date ->
                    if (draft?.date == null) draft?.date = parseDate(date)
                }
                pending = null; continue
            }
            if (pending != null && draft != null) {
                val key = pending!!; pending = null
                if (key == "date") { val parsed = parseDate(text); if (parsed != null) { draft!!.date = parsed; continue } }
                else {
                    val value = parseNumber(text)
                    if (value != null && (key in listOf("profit", "amount") || value.signum() >= 0)) { draft!!.values[key] = value; continue }
                }
            }
            if (totalRow.containsMatchIn(text)) { finish(); draft = null; pending = null; continue }
            if (Regex("^(昨日收益|累计收益|可用份额|冻结份额|在途份额)").containsMatchIn(text)) { pending = null; continue }
            val dateMatch = dateLabel.matchEntire(text)
            if (dateMatch != null) {
                if (draft != null) { draft!!.date = parseDate(dateMatch.groupValues[1]); if (draft!!.date == null && dateMatch.groupValues[1].isBlank()) pending = "date" }
                continue
            }
            val metrics = metricPattern.findAll(text).filter { m -> text.getOrNull(m.range.last + 1) != '率' }.toList()
            val heading = text.substringBeforeMetric(metrics.firstOrNull()?.range?.first).let { if (badgeStart != null) it.take(minOf(it.length, badgeStart)) else it }
            val possibleName = cleanName(heading.replace(codeLabel, "").let { codePattern.replace(it, "") })
            val isName = !navDatePattern.containsMatchIn(text) && isFundName(possibleName)
            val explicitCode = codeLabel.find(text)?.groupValues?.get(1)
            val code = explicitCode ?: if (isName || (metrics.isEmpty() && draft?.name?.isNotBlank() == true && draft?.code == null)) {
                val candidate = codePattern.find(heading)?.value
                if (candidate != null && !heading.contains('.') && !heading.contains('%') && !heading.contains('¥') && !heading.contains('￥') && !heading.contains(',')) candidate else null
            } else null
            if (isName) {
                if (draft?.name?.isNotBlank() == true) finish()
                if (draft?.name?.isBlank() != true) draft = Draft()
                draft!!.name = possibleName; if (code != null) draft!!.code = code; pending = null
            } else if (code != null) {
                if (draft == null) draft = Draft()
                if (draft!!.code != null && draft!!.code != code) { finish(); draft = Draft() }
                draft!!.code = code; pending = null
            }
            val current = draft ?: continue
            val navDate = navDatePattern.find(text)?.groupValues?.get(1)
            if (navDate != null && current.date == null) {
                current.date = parseDate(navDate)
            }
            if (metrics.isNotEmpty()) {
                pending = null
                metrics.forEachIndexed { i, match ->
                    if (match.value == "金额" && text.substring(0, match.range.first).endsWith("待确认")) return@forEachIndexed
                    val key = labels.getValue(match.value)
                    val nextMetric = metrics.getOrNull(i + 1)?.range?.first ?: text.length
                    val pendingStart = text.indexOf("待确认", match.range.last + 1).takeIf { it >= 0 } ?: text.length
                    val end = minOf(nextMetric, pendingStart)
                    val span = text.substring(match.range.last + 1, end).replace(Regex("[（(](元|份)[）)]|[①②③④⑤⑥⑦⑧⑨⑩ⓘ?]"), "").trim().trimStart(':', '：').trim()
                    val value = parseNumber(span)
                    if (value != null && (key in listOf("profit", "amount") || value.signum() >= 0)) current.values[key] = value
                    else if (metrics.size == 1 && span.isBlank()) pending = key
                }
            }
        }
        finish()
        val unique = result.distinctBy { listOf(it.name, it.code, it.shares, it.cost, it.amount, it.profit) }
        require(unique.size <= 50) { "单张截图超过 50 条基金，请裁剪后分批导入" }
        return unique
    }
    private fun String.substringBeforeMetric(index: Int?) = if (index == null) this else substring(0, index)
    private fun normalize(text: String): String = text.replace('−', '-').replace('，', ',').replace('．', '.').replace('：', ':')
        .replace(Regex("(?<=[\u4e00-\u9fff])\\s+(?=[\u4e00-\u9fff])"), "").replace("持有人金额", "持有金额").trim()
    private fun cleanName(text: String) = text.replace(Regex("^(?:基金名称|基金简称)[:：\\s]*"), "")
        .replace(Regex("\\s+[+\\-￥¥]?[0-9][0-9,]*\\.[0-9]+.*$"), "").replace(Regex("\\s+"), "").trim(':', '：', '-', ' ')
    private fun isFundName(text: String): Boolean {
        if (text.length !in 3..120 || text.count { it in '\u4e00'..'\u9fff' } < 2) return false
        if (Regex("^(我的基金|基金总|基金资产|基金账户|基金经理|基金详情|基金档案|基金持仓|持仓明细|总金额|总资产|昨日收益|累计收益|持有收益|持有份额|单位净值|成本金额)").containsMatchIn(text)) return false
        return Regex("基金|混合|指数|债券|股票|联接|ETF|LOF|货币|养老|增强|成长|精选|稳健|中证|沪深").containsMatchIn(text)
    }
    private fun parseNumber(raw: String): BigDecimal? {
        val token = numberPattern.matchEntire(raw.trim())?.groupValues?.get(1) ?: return null
        val clean = token.replace(",", "")
        return clean.toBigDecimalOrNull()?.takeIf { it.precision() <= 32 && it.scale() in 0..18 }
    }
    private fun parseDate(raw: String): String? = runCatching {
        val values = raw.trim().split(Regex("[-/.]"))
        if (values.size == 3) {
            LocalDate.of(values[0].toInt(), values[1].toInt(), values[2].toInt()).toString()
        } else if (values.size == 2) {
            val now = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            var d = LocalDate.of(now.year, values[0].toInt(), values[1].toInt())
            if (d.isAfter(now)) d = d.minusYears(1)
            d.toString()
        } else null
    }.getOrNull()
}
