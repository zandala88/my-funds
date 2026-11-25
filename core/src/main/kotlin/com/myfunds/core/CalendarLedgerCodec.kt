package com.myfunds.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

/** Version-two ledger fields are bounded and validated independently of live holdings. */
internal object CalendarLedgerCodec {
    private fun text(o: JSONObject,k: String)= (o.get(k) as? String)?.also { require(it.length<=500) } ?: throw IllegalArgumentException("字段 $k 必须是文本")
    private fun optional(o: JSONObject,k: String)=if(!o.has(k) || o.isNull(k)) null else text(o,k)
    private fun decimal(o: JSONObject,k: String)=optional(o,k)?.let { require(it.matches(Regex("-?[0-9]{1,32}(\\.[0-9]{1,18})?"))); BigDecimal(it) }
    private fun bool(o: JSONObject,k: String)=o.get(k) as? Boolean ?: throw IllegalArgumentException("字段 $k 必须是布尔值")
    private fun number(o: JSONObject,k: String)=o.get(k).let { require(it is Number && it.toString().matches(Regex("[0-9]+"))); it.toString().toInt() }
    fun schedule(s: DcaSchedule)=JSONObject().put("frequency",s.frequency.name).put("startDate",s.startDate).put("weekDay",s.weekDay).put("monthDay",s.monthDay)
        .put("endDate",s.endDate ?: JSONObject.NULL).put("holidayPolicy",s.holidayPolicy.name).put("paused",s.paused).put("currency",s.currency).put("revision",s.revision).put("activeFrom",s.activeFrom ?: JSONObject.NULL)
    fun schedule(o: JSONObject)=DcaSchedule(DcaFrequency.valueOf(text(o,"frequency")),text(o,"startDate"),number(o,"weekDay"),number(o,"monthDay"),optional(o,"endDate"),
        HolidayPolicy.valueOf(text(o,"holidayPolicy")),bool(o,"paused"),text(o,"currency"),text(o,"revision"),optional(o,"activeFrom")).also { it.validate() }
    fun incomes(rows: List<IncomeEntry>)=JSONArray().also { a->rows.forEach { r->a.put(JSONObject().put("holdingId",r.holdingId).put("code",r.code).put("name",r.name).put("groupId",r.groupId)
        .put("date",r.date).put("currency",r.currency).put("kind",r.kind.name).put("amount",r.amount?.toPlainString() ?: JSONObject.NULL).put("shares",r.shares?.toPlainString() ?: JSONObject.NULL)
        .put("source",r.source).put("asOf",r.asOf).put("note",r.note).put("invalidated",r.invalidated)) } }
    fun incomes(a: JSONArray): List<IncomeEntry> { require(a.length()<=200000); return (0 until a.length()).map { a.getJSONObject(it).let { o->
        IncomeEntry(text(o,"holdingId"),text(o,"code"),text(o,"name"),text(o,"groupId"),text(o,"date"),text(o,"currency"),IncomeKind.valueOf(text(o,"kind")),decimal(o,"amount"),decimal(o,"shares"),text(o,"source"),text(o,"asOf"),text(o,"note"),bool(o,"invalidated"))
    } } }
    fun executions(rows: List<DcaExecution>)=JSONArray().also { a->rows.forEach { r->a.put(JSONObject().put("id",r.id).put("holdingId",r.holdingId).put("code",r.code).put("name",r.name).put("revision",r.revision)
        .put("plannedDate",r.plannedDate).put("executionDate",r.executionDate ?: JSONObject.NULL).put("status",r.status.name).put("currency",r.currency).put("amount",r.amount?.toPlainString() ?: JSONObject.NULL)
        .put("shares",r.shares?.toPlainString() ?: JSONObject.NULL).put("price",r.price?.toPlainString() ?: JSONObject.NULL).put("tradeId",r.tradeId ?: JSONObject.NULL).put("note",r.note)) } }
    fun executions(a: JSONArray): List<DcaExecution> { require(a.length()<=100000); return (0 until a.length()).map { a.getJSONObject(it).let { o->
        DcaExecution(text(o,"id"),text(o,"holdingId"),text(o,"code"),text(o,"name"),text(o,"revision"),text(o,"plannedDate"),optional(o,"executionDate"),DcaStatus.valueOf(text(o,"status")),text(o,"currency"),decimal(o,"amount"),decimal(o,"shares"),decimal(o,"price"),optional(o,"tradeId"),text(o,"note"))
    } } }
    fun validate(incomes: List<IncomeEntry>,executions: List<DcaExecution>) {
        require(incomes.size<=200000 && executions.size<=100000) { "日历或定投记录数量超过限制" }
        require(incomes.map { it.key }.distinct().size==incomes.size && executions.map { it.id }.distinct().size==executions.size) { "日历或定投记录重复" }
        fun identity(s: String)=require(s.isNotBlank() && s.length<=100)
        fun info(code: String,name: String,currency: String) { require(code.matches(Regex("[0-9]{6}")) && name.isNotBlank() && name.length<=200 && currency.matches(Regex("[A-Z]{3}"))) }
        incomes.forEach { r->
            identity(r.holdingId); identity(r.groupId); info(r.code,r.name,r.currency); LocalDate.parse(r.date)
            require(r.source.length<=500 && r.asOf.length<=100 && r.note.length<=500)
            r.amount?.let { validDecimal(it,true) }; r.shares?.let { validDecimal(it) }
            require((r.kind==IncomeKind.UNKNOWN)==(r.amount==null)) { "收益状态与金额不一致" }
        }
        executions.forEach { r->
            identity(r.id); identity(r.holdingId); identity(r.revision); info(r.code,r.name,r.currency); LocalDate.parse(r.plannedDate); r.executionDate?.let { LocalDate.parse(it); require(it>=r.plannedDate) }
            require(r.note.length<=500); r.tradeId?.let { identity(it) }; listOfNotNull(r.amount,r.shares,r.price).forEach { validDecimal(it) }
            require(r.status!=DcaStatus.RECORDED || r.tradeId!=null && r.shares?.signum()==1 && r.price?.signum()==1)
        }
    }
}
