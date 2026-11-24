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
    fun parse(text: String): List<HoldingCandidate> = parse(text.lines().take(3000).mapIndexed { i, s -> OcrLine(s, 0, i * 40, 1000, i * 40 + 30) })

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
        val result = mutableListOf<HoldingCandidate>(); var draft: Draft? = null; var pending: String? = null
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
            val text = normalize(row.sortedBy { it.left }.joinToString(" ") { it.text }).take(2000)
            if (text.isBlank()) continue
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
            val possibleName = cleanName(text.substringBeforeMetric(metrics.firstOrNull()?.range?.first).replace(codeLabel, "").let { codePattern.replace(it, "") })
            val isName = isFundName(possibleName)
            val explicitCode = codeLabel.find(text)?.groupValues?.get(1)
            val code = explicitCode ?: if (metrics.isEmpty() && (isName || (draft?.name?.isNotBlank() == true && draft?.code == null))) {
                val candidate = codePattern.find(text)?.value
                if (candidate != null && !text.contains('.') && !text.contains('%') && !text.contains('¥') && !text.contains('￥') && !text.contains(',')) candidate else null
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
                    val end = metrics.getOrNull(i + 1)?.range?.first ?: text.length
                    val span = text.substring(match.range.last + 1, end).replace(Regex("[（(](元|份)[）)]|[①②③④⑤⑥⑦⑧⑨⑩ⓘ?]"), "").trim().trimStart(':', '：').trim()
                    val value = parseNumber(span)
                    if (value != null && (key in listOf("profit", "amount") || value.signum() >= 0)) current.values[key] = value
                    else if (metrics.size == 1 && span.isBlank()) pending = key
                }
            }
        }
        finish()
        return result.distinctBy { listOf(it.name, it.code, it.shares, it.cost, it.amount, it.profit) }.take(50)
    }
    private fun String.substringBeforeMetric(index: Int?) = if (index == null) this else substring(0, index)
    private fun normalize(text: String): String = text.replace('−', '-').replace('，', ',').replace('．', '.').replace('：', ':')
        .replace(Regex("(?<=[\u4e00-\u9fff])\\s+(?=[\u4e00-\u9fff])"), "").trim()
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
