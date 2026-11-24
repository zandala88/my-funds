package com.myfunds.core

import org.json.JSONArray
import org.json.JSONObject

/** Uncommitted text fields intentionally allow partial numeric/date edits. */
data class OcrDraftRow(val name: String, val code: String, val shares: String, val cost: String, val date: String,
    val mode: Int, val selected: Boolean, val reference: String)
data class OcrReviewDraft(val text: String, val defaultDate: String, val rows: List<OcrDraftRow>)
object OcrDraftCodec {
    private const val MAX_BYTES = 192 * 1024
    private fun validate(d: OcrReviewDraft) {
        require(d.text.length <= 20000 && d.defaultDate.length <= 10 && d.rows.size <= 50)
        d.rows.forEach { require(it.name.length <= 200 && it.code.length <= 6 && it.shares.length <= 64 && it.cost.length <= 64 && it.date.length <= 10 && it.mode in 0..2 && it.reference.length <= 500) }
    }
    fun encode(d: OcrReviewDraft): String {
        validate(d)
        return JSONObject().put("version", 1).put("text", d.text).put("defaultDate", d.defaultDate)
            .put("rows", JSONArray().also { a -> d.rows.forEach { r -> a.put(JSONObject().put("name", r.name).put("code", r.code)
                .put("shares", r.shares).put("cost", r.cost).put("date", r.date).put("mode", r.mode).put("selected", r.selected).put("reference", r.reference)) } }).toString()
            .also { require(it.toByteArray().size <= MAX_BYTES) }
    }
    fun decode(raw: String): OcrReviewDraft = try {
        require(raw.toByteArray().size <= MAX_BYTES); val o = JSONObject(raw); require(o.get("version").toString() == "1")
        val a = o.getJSONArray("rows"); require(a.length() <= 50)
        val result = OcrReviewDraft(o.getString("text"), o.getString("defaultDate"), (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            OcrDraftRow(r.getString("name"), r.getString("code"), r.getString("shares"), r.getString("cost"), r.getString("date"), r.getInt("mode"), r.getBoolean("selected"), r.getString("reference"))
        })
        validate(result); result
    } catch (_: Exception) { throw IllegalArgumentException("未保存的核对草稿无法恢复，原持仓未修改") }
}
