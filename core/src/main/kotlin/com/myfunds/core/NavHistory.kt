package com.myfunds.core

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class DailyNav(val code: String,val date: String,val nav: BigDecimal,val previousNav: BigDecimal?=null,val referenceDate: String?=null,
    val change: BigDecimal?=null,val incomePerTenThousand: BigDecimal?=null,val currency: String="CNY",val kind: FundKind=FundKind.UNKNOWN,
    val corporateAction: Boolean=false,val final: Boolean=true,val market: Boolean=false,val source: String="公布净值",val asOf: String=date)
data class NavHistory(val code: String,val from: String,val through: String,val points: List<DailyNav>,val complete: Boolean,val invalidDates: Set<String> = emptySet())
object NavHistoryParser {
    private fun number(o: JSONObject,key: String)=MarketParser.number(o.opt(key))
    private fun date(value: String?)=value?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
    fun parse(raw: String,code: String,from: String,through: String,currency: String="CNY",kind: FundKind=FundKind.UNKNOWN): NavHistory {
        require(code.matches(Regex("[0-9]{6}"))); LocalDate.parse(from); LocalDate.parse(through); require(from<=through)
        val root=MarketParser.root(raw)
        val data=root.optJSONObject("Data") ?: throw IllegalArgumentException("暂无每日净值数据")
        val rows=data.optJSONArray("LSJZList") ?: throw IllegalArgumentException("暂无每日净值列表")
        require(rows.length()<=1000)
        val money=data.optString("SYType").contains("万份") || (data.optString("FundType")=="005" && kind!=FundKind.FLOATING_MONEY) || kind==FundKind.MONEY
        val invalid=mutableSetOf<String>()
        val parsed=(0 until rows.length()).mapNotNull { i ->
            val row=rows.optJSONObject(i) ?: return@mapNotNull null
            val day=date(MarketParser.text(row,"FSRQ")) ?: return@mapNotNull null
            if (day !in from..through) return@mapNotNull null
            val wire=number(row,"DWJZ")
            if (wire==null || (!money && wire.signum()<=0)) { invalid.add(day); return@mapNotNull null }
            val action=listOf("FHFCZ","FHFCZ10","FHFCBZ","FHSP").any { key -> MarketParser.text(row,key)?.let { it !in listOf("0","0.00") }==true }
            DailyNav(code,day,if(money) BigDecimal.ONE else wire,change=number(row,"JZZZL"),incomePerTenThousand=if(money) wire else null,
                currency=currency,kind=if(money) FundKind.MONEY else kind,corporateAction=action)
        }.distinctBy { it.date }.sortedBy { it.date }
        val linked=parsed.mapIndexed { index,p ->
            val previous=parsed.getOrNull(index-1)?.takeUnless { old -> invalid.any { it>old.date && it<p.date } }
            p.copy(previousNav=previous?.nav,referenceDate=previous?.date)
        }
        val count=root.opt("TotalCount")?.toString()?.toIntOrNull()
        return NavHistory(code,from,through,linked,count!=null && count==rows.length() && invalid.isEmpty(),invalid)
    }
    fun market(raw: String,code: String,symbol: String,from: String,through: String,now: String): NavHistory {
        val root=JSONObject(raw); require(root.optInt("code",-1)==0) { "场内历史行情不可用" }
        val rows=root.optJSONObject("data")?.optJSONObject(symbol)?.optJSONArray("day") ?: throw IllegalArgumentException("缺少未复权场内收盘价")
        require(rows.length()<=1000)
        val current=LocalDateTime.parse(now)
        val points=(0 until rows.length()).mapNotNull { i ->
            val row=rows.optJSONArray(i) ?: return@mapNotNull null
            val day=date(row.optString(0)) ?: return@mapNotNull null
            val close=MarketParser.number(row.opt(2))?.takeIf { it.signum()>0 } ?: return@mapNotNull null
            if(day !in from..through || day>current.toLocalDate().toString()) return@mapNotNull null
            DailyNav(code,day,close,final=day<current.toLocalDate().toString() || current.toLocalTime()>=LocalTime.of(15,5),market=true,
                source="场内收盘价变动（未计现金分红）",asOf=if(day==current.toLocalDate().toString()) now.replace('T',' ') else day)
        }.distinctBy { it.date }.sortedBy { it.date }
        return NavHistory(code,from,through,points.mapIndexed { i,p ->p.copy(previousNav=points.getOrNull(i-1)?.nav,referenceDate=points.getOrNull(i-1)?.date) },true)
    }
}
