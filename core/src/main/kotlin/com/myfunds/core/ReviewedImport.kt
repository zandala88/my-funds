package com.myfunds.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class ImportMode { WATCH, HOLDING, UPDATE }
data class ReviewedHolding(val code: String, val name: String, val shares: BigDecimal?, val cost: BigDecimal?, val confirmedDate: String, val mode: ImportMode)
object ReviewedImport {
    const val MAX_RESULT_BYTES = 128 * 1024
    fun apply(current: Portfolio, rows: List<ReviewedHolding>, groupId: String): Portfolio {
        current.validate(); require(rows.size <= 50) { "一次最多导入 50 只基金" }
        require(rows.map { it.code }.distinct().size == rows.size) { "同一截图中选中了重复基金代码，请取消重复条目" }
        require(current.groups.any { it.id == groupId }) { "目标分组已不存在" }
        var next = current
        rows.forEach { r ->
            require(r.code.matches(Regex("[0-9]{6}")) && r.name.isNotBlank() && r.name.length <= 200) { "请核对六位基金代码和名称" }
            require(runCatching { LocalDate.parse(r.confirmedDate) }.isSuccess && r.confirmedDate <= LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) { "持仓确认日期无效或尚未发生" }
            val existing = next.holdings.firstOrNull { it.code == r.code }
            if (r.mode == ImportMode.WATCH && existing != null) return@forEach
            if (r.mode == ImportMode.UPDATE) {
                require(existing != null) { "${r.code} 尚未存在，请选择录入新持仓" }
                next = PortfolioOperations.calibrate(next, existing.id, r.shares ?: throw IllegalArgumentException("请补齐份额"), r.cost ?: throw IllegalArgumentException("请补齐平均单位成本"), r.confirmedDate)
            } else {
                require(existing == null) { "${r.code} 已存在，请明确选择更新已有持仓或只加自选" }
                val shares = if (r.mode == ImportMode.WATCH) BigDecimal.ZERO else r.shares ?: throw IllegalArgumentException("请补齐份额")
                val cost = if (r.mode == ImportMode.WATCH) BigDecimal.ZERO else r.cost ?: throw IllegalArgumentException("请补齐平均单位成本")
                validDecimal(shares); validDecimal(cost)
                next = next.copy(holdings = next.holdings + Holding(UUID.randomUUID().toString(), r.code, r.name.trim(), groupId, shares, cost, next.holdings.size, confirmedDate = r.confirmedDate, initialShares = shares))
            }
        }
        return next.validate()
    }
    fun encode(rows: List<ReviewedHolding>): String = JSONArray().also { a -> rows.forEach { r -> a.put(JSONObject().put("code", r.code).put("name", r.name)
        .put("shares", r.shares?.toPlainString() ?: JSONObject.NULL).put("cost", r.cost?.toPlainString() ?: JSONObject.NULL)
        .put("date", r.confirmedDate).put("mode", r.mode.name)) } }.toString().also { require(it.toByteArray().size <= MAX_RESULT_BYTES) { "识别结果过多，请分次导入" } }
    fun decode(raw: String): List<ReviewedHolding> = try {
        require(raw.toByteArray().size <= MAX_RESULT_BYTES); val a = JSONArray(raw); require(a.length() <= 50)
        (0 until a.length()).map { i -> val o = a.getJSONObject(i)
            fun decimal(key: String): BigDecimal? {
                if (o.isNull(key)) return null
                val value = o.get(key) as? String ?: throw IllegalArgumentException("数值字段无效")
                require(value.matches(Regex("[0-9]{1,32}(\\.[0-9]{1,18})?"))) { "数值字段无效" }
                return BigDecimal(value)
            }
            ReviewedHolding(o.getString("code"), o.getString("name"), decimal("shares"), decimal("cost"), o.getString("date"), ImportMode.valueOf(o.getString("mode")))
        }
    } catch (_: Exception) { throw IllegalArgumentException("截图核对结果无效，原持仓未修改") }
}
