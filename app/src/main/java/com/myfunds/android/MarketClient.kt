package com.myfunds.android

import com.myfunds.core.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.Collections

class MarketClient {
    private val connections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    fun cancelAll() { synchronized(connections) { connections.toList().forEach { it.disconnect() }; connections.clear() } }
    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connections.add(connection)
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 12000
            connection.setRequestProperty("User-Agent", "MyFunds/0.1 Android")
            require(connection.responseCode in 200..299) { "行情请求失败（${connection.responseCode}）" }
            return connection.inputStream.use { input ->
                val bytes = readBounded(input, 4 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connections.remove(connection); connection.disconnect() }
    }
    private fun params(values: Map<String, String>) = values.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
    private fun fund(path: String, extra: Map<String, String>) = get("https://fundmobapi.eastmoney.com/$path?" +
        params(mapOf("deviceid" to "Wap", "plat" to "Wap", "product" to "EFund", "version" to "2.0.0") + extra))
    fun search(query: String) = MarketParser.search(get("https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?" + params(mapOf("m" to "9", "key" to query))))
    fun quotes(codes: List<String>): List<FundQuote> = codes.chunked(100).flatMap { chunk ->
        MarketParser.quotes(fund("FundMNewApi/FundMNFInfo", mapOf("pageIndex" to "1", "pageSize" to "200", "Fcodes" to chunk.joinToString(","))))
            .map { it.copy(fetchedAt = Instant.now().toString()) }
    }
    fun history(code: String, range: String = "y", yield: Boolean = false): List<ChartPoint> = MarketParser.history(
        fund("FundMApi/${if (yield) "FundYieldDiagramNew" else "FundNetDiagram"}.ashx", mapOf("FCODE" to code, "RANGE" to range)), if (yield) "YIELD" else "DWJZ")
    fun estimateHistory(code: String) = MarketParser.estimateHistory(fund("FundMApi/FundVarietieValuationDetail.ashx", mapOf("FCODE" to code)))
    fun information(code: String): List<Pair<String, String>> {
        val data = MarketParser.root(fund("FundMApi/FundBaseTypeInformation.ashx", mapOf("FCODE" to code))).optJSONObject("Datas") ?: return emptyList()
        return fields(data, mapOf("JJMC" to "基金名称", "FCODE" to "基金代码", "FTYPE" to "基金类型", "FUNDTYPE" to "基金类型", "ESTABDATE" to "成立日期", "DWJZ" to "单位净值", "LJJZ" to "累计净值", "ENDNAV" to "基金规模（元）", "FSRQ" to "净值日期", "JJGS" to "基金公司", "JJJL" to "基金经理", "SGZT" to "申购状态", "SHZT" to "赎回状态", "BENCH" to "业绩基准", "INVESTMENTIDEA" to "投资理念", "INVESTMENTTARGET" to "投资目标"))
    }
    fun managers(code: String): List<Pair<String, String>> {
        val raw = MarketParser.root(fund("FundMApi/FundManagerList.ashx", mapOf("FCODE" to code)))
        val a = raw.optJSONArray("Datas") ?: return emptyList()
        val records = (0 until a.length()).flatMap { i -> a.optJSONObject(i)?.let { fields(it, mapOf("MGRNAME" to "经理", "DAYS" to "任职天数", "PENAVGROWTH" to "任职涨幅（%）", "FEMPDATE" to "任职日期", "LEMPDATE" to "结束日期")) } ?: emptyList() }
        val detail = runCatching { MarketParser.root(fund("FundMApi/FundMangerDetail.ashx", mapOf("FCODE" to code))).optJSONArray("Datas") }.getOrNull()
        return records + (0 until (detail?.length() ?: 0)).flatMap { i -> fields(detail!!.getJSONObject(i), mapOf("MGRNAME" to "现任经理", "RESUME" to "简介")) }
    }
    fun positions(code: String): List<Pair<String, String>> {
        val raw = MarketParser.root(fund("FundMNewApi/FundMNInverstPosition", mapOf("FCODE" to code)))
        val data = raw.optJSONObject("Datas") ?: return emptyList()
        val a = data.optJSONArray("fundStocks") ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        raw.optJSONObject("Expansion")?.let { fields(it, mapOf("FSRQ" to "披露日期", "PDATE" to "披露日期")).let(result::addAll) }
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            result.add((MarketParser.text(o, "GPJC") ?: MarketParser.text(o, "GPDM") ?: "股票") to
                "${MarketParser.text(o, "GPDM") ?: ""}  占净值 ${MarketParser.text(o, "JZBL") ?: "暂无"}%")
        }
        return result
    }
    private fun fields(o: JSONObject, labels: Map<String, String>) = labels.mapNotNull { (key, label) ->
        MarketParser.text(o, key)?.let { label to it.replace(Regex("<[^>]*>"), "").take(4000) }
    }.distinct()
}
