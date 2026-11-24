package com.myfunds.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal
import java.time.Instant

object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(p: Portfolio): String {
        p.validate()
        val root = JSONObject().put("format", "my-funds-backup").put("schemaVersion", 1).put("exportedAt", Instant.now().toString())
        root.put("holdings", JSONArray().also { a -> p.holdings.forEach { h -> a.put(JSONObject()
            .put("id", h.id).put("code", h.code).put("name", h.name).put("groupId", h.groupId)
            .put("shares", h.shares.toPlainString()).put("cost", h.cost.toPlainString()).put("order", h.order).put("starred", h.starred)) } })
        root.put("groups", JSONArray().also { a -> p.groups.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("trades", JSONArray().also { a -> p.trades.forEach { t -> a.put(JSONObject().put("id", t.id).put("holdingId", t.holdingId)
            .put("kind", t.kind).put("shares", t.shares.toPlainString()).put("price", t.price.toPlainString())
            .put("fee", t.fee.toPlainString()).put("date", t.date).put("realized", t.realized.toPlainString())) } })
        val s = p.settings
        root.put("settings", JSONObject().put("darkMode", s.darkMode).put("autoRefresh", s.autoRefresh).put("sort", s.sort)
            .put("showShares", s.showShares).put("showCost", s.showCost).put("indexCodes", JSONArray(s.indexCodes)))
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 8MB 限制" } }
    }

    fun decode(raw: String): Portfolio = try {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 8MB 限制" }
        var depth = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 20) { "备份嵌套过深" } }; '}', ']' -> depth-- }
        }
        val tokener = JSONTokener(raw)
        val root = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("不是有效的备份文件")
        require(tokener.nextClean() == '\u0000') { "备份包含多余内容" }
        require(root.getString("format") == "my-funds-backup") { "不是 My Funds 备份" }
        require(root.get("schemaVersion").toString() == "1") { "备份版本不兼容，请更新应用" }
        require(runCatching { Instant.parse(root.getString("exportedAt")) }.isSuccess) { "备份时间无效" }
        fun text(o: JSONObject, key: String): String = (o.get(key) as? String)?.also { require(it.length <= 300) } ?: throw IllegalArgumentException("字段 $key 必须是文本")
        fun decimal(o: JSONObject, key: String): BigDecimal {
            val t = text(o, key)
            require(t.matches(Regex("-?[0-9]{1,32}(\\.[0-9]{1,18})?"))) { "字段 $key 数值无效" }
            return BigDecimal(t)
        }
        fun bool(o: JSONObject, key: String) = o.get(key) as? Boolean ?: throw IllegalArgumentException("字段 $key 必须是布尔值")
        fun <T> rows(key: String, max: Int, f: (JSONObject) -> T): List<T> {
            val a = root.getJSONArray(key); require(a.length() <= max) { "记录数量过多" }
            return (0 until a.length()).map { f(a.getJSONObject(it)) }
        }
        val holdings = rows("holdings", 10000) { o -> Holding(text(o, "id"), text(o, "code"), text(o, "name"), text(o, "groupId"),
            decimal(o, "shares"), decimal(o, "cost"), o.getInt("order"), bool(o, "starred")) }
        val groups = rows("groups", 1000) { Group(text(it, "id"), text(it, "name")) }
        val trades = rows("trades", 100000) { o -> Trade(text(o, "id"), text(o, "holdingId"), text(o, "kind"),
            decimal(o, "shares"), decimal(o, "price"), decimal(o, "fee"), text(o, "date"), decimal(o, "realized")) }
        val s = root.getJSONObject("settings"); val indices = s.getJSONArray("indexCodes")
        require(indices.length() <= 12)
        Portfolio(holdings, groups, trades, AppSettings(bool(s, "darkMode"), bool(s, "autoRefresh"), text(s, "sort"),
            bool(s, "showShares"), bool(s, "showCost"), (0 until indices.length()).map { indices.getString(it) })).validate()
    } catch (e: IllegalArgumentException) { throw e }
      catch (_: Exception) { throw IllegalArgumentException("备份文件损坏或缺少必要字段") }
}
