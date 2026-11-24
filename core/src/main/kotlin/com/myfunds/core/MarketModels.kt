package com.myfunds.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SearchResult(val code: String, val name: String)
data class FundQuote(val code: String, val name: String, val nav: BigDecimal?, val date: String?,
    val change: BigDecimal? = null, val estimate: BigDecimal? = null, val estimateChange: BigDecimal? = null,
    val estimateTime: String? = null, val fetchedAt: String = "")
data class ChartPoint(val date: String, val value: BigDecimal)
data class IndexQuote(val code: String, val name: String, val price: BigDecimal?, val change: BigDecimal?, val time: String? = null, val netFlow: BigDecimal? = null)

object MarketParser {
    fun number(value: Any?): BigDecimal? = value?.takeUnless { it == JSONObject.NULL }?.toString()
        ?.takeIf { it.length <= 60 && it.matches(Regex("-?[0-9]+(\\.[0-9]+)?")) }
        ?.toBigDecimalOrNull()?.takeIf { it.precision() <= 32 && it.scale() <= 18 }
    fun text(o: JSONObject, key: String): String? = o.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()
        ?.takeIf { it.isNotBlank() && it !in listOf("--", "-", "null") }

    fun root(raw: String): JSONObject = try {
        val o = JSONObject(raw)
        require(o.opt("Success") != false && (o.opt("ErrCode") == null || o.optInt("ErrCode") == 0) && o.optInt("rc", 0) == 0) {
            text(o, "ErrMsg") ?: "行情服务暂不可用"
        }
        o
    } catch (e: IllegalArgumentException) { throw e }
      catch (_: Exception) { throw IllegalArgumentException("行情服务返回了无法识别的数据") }

    private fun rows(raw: String): List<JSONObject> {
        val a = root(raw).optJSONArray("Datas") ?: throw IllegalArgumentException("行情服务暂无数据")
        return (0 until a.length().coerceAtMost(20000)).mapNotNull { a.optJSONObject(it) }
    }
    fun search(raw: String): List<SearchResult> = rows(raw).mapNotNull { o ->
        val code = text(o, "CODE"); val name = text(o, "NAME")
        if (code?.matches(Regex("[0-9]{6}")) == true && name != null) SearchResult(code, name.take(200)) else null
    }.distinctBy { it.code }

    fun quotes(raw: String): List<FundQuote> = rows(raw).mapNotNull { o ->
        val code = text(o, "FCODE")
        if (code?.matches(Regex("[0-9]{6}")) != true) return@mapNotNull null
        val nav = number(o.opt("NAV"))?.takeIf { it > BigDecimal.ZERO }
        val estimate = number(o.opt("GSZ"))?.takeIf { it > BigDecimal.ZERO }
        val time = text(o, "GZTIME")
        val date = text(o, "PDATE")?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        FundQuote(code, text(o, "SHORTNAME") ?: code, nav, date, number(o.opt("NAVCHGRT")),
            estimate, if (estimate != null && time != null) number(o.opt("GSZZL")) else null, time, text(o, "fetchedAt") ?: "")
    }

    fun encodeQuotes(quotes: List<FundQuote>): String = JSONObject().put("Datas", JSONArray().also { a -> quotes.forEach { q ->
        a.put(JSONObject().put("FCODE", q.code).put("SHORTNAME", q.name).put("NAV", q.nav?.toPlainString() ?: JSONObject.NULL)
            .put("PDATE", q.date ?: JSONObject.NULL).put("NAVCHGRT", q.change?.toPlainString() ?: JSONObject.NULL)
            .put("GSZ", q.estimate?.toPlainString() ?: JSONObject.NULL).put("GSZZL", q.estimateChange?.toPlainString() ?: JSONObject.NULL)
            .put("GZTIME", q.estimateTime ?: JSONObject.NULL).put("fetchedAt", q.fetchedAt))
    } }).toString()

    fun history(raw: String, valueKey: String = "DWJZ"): List<ChartPoint> = rows(raw).mapNotNull { o ->
        val value = number(o.opt(valueKey)); val date = text(o, "FSRQ") ?: text(o, "PDATE")
        if (value != null && date != null) ChartPoint(date, value) else null
    }.sortedBy { it.date }

    fun estimateHistory(raw: String): List<ChartPoint> {
        val a = root(raw).optJSONArray("Datas") ?: throw IllegalArgumentException("暂无盘中估值曲线")
        return (0 until a.length()).mapNotNull { i ->
            val parts = a.optString(i).split(','); val value = parts.getOrNull(2)?.let { number(it) }
            if (value != null) ChartPoint(parts.first(), value) else null
        }
    }

    fun indices(raw: String): List<IndexQuote> {
        val diff = root(raw).optJSONObject("data")?.optJSONArray("diff") ?: throw IllegalArgumentException("暂无证券行情")
        return (0 until diff.length()).mapNotNull { i ->
            val o = diff.optJSONObject(i) ?: return@mapNotNull null
            val code = text(o, "f12") ?: return@mapNotNull null
            val time = text(o, "quoteTime") ?: number(o.opt("f124"))?.toLong()?.takeIf { it > 0 }?.let {
                runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(it)) }.getOrNull()
            }
            IndexQuote("${o.optInt("f13", 1)}.$code", text(o, "f14") ?: code, number(o.opt("f2")), number(o.opt("f3")), time, number(o.opt("f62")))
        }
    }
}
