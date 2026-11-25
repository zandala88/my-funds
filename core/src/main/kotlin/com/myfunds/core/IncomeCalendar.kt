package com.myfunds.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class OpeningShares(val shares: BigDecimal?,val reason: String="")
object HoldingTimeline {
    fun opening(p: Portfolio,h: Holding,day: String): OpeningShares {
        val trades=p.trades.filter { it.holdingId==h.id }
        if(trades.any { it.date==day && it.kind=="CALIBRATE" }) return OpeningShares(null,"当日持仓校准，无法还原期初份额")
        var shares=h.initialShares?.takeIf { h.confirmedDate!=null && (h.confirmedDate<day || (h.confirmedDate==day && it.signum()==0)) }
        for(t in trades.filter { it.date<day }.sortedBy { it.date }) {
            shares=when(t.kind) { "CALIBRATE" -> t.shares; "BUY","DCA" -> shares?.add(t.shares); "SELL" -> shares?.subtract(t.shares); else -> shares }
        }
        return if(shares!=null && shares.signum()>=0) OpeningShares(shares) else OpeningShares(null,"缺少可还原的期初持仓，请确认持仓基准")
    }
}
enum class IncomeKind { CONFIRMED, ESTIMATE, PARTIAL, UNKNOWN }
data class IncomeEntry(val holdingId: String,val code: String,val name: String,val groupId: String,val date: String,val currency: String,
    val kind: IncomeKind,val amount: BigDecimal?,val shares: BigDecimal?,val source: String,val asOf: String,val note: String="",val invalidated: Boolean=false) {
    val key get()="$code:$date:$currency"
}
data class IncomeSummary(val confirmed: BigDecimal,val estimated: BigDecimal,val partial: BigDecimal,val missing: Int,val count: Int,val provisional: Int=0) {
    val complete get()=missing==0 && provisional==0 && count>0
}
object IncomeCalendar {
    fun fromHistory(p: Portfolio,h: Holding,history: NavHistory,month: YearMonth,calendar: TradingCalendar): List<IncomeEntry> {
        val points=history.points.associateBy { it.date }
        val from=maxOf(month.atDay(1).toString(),h.confirmedDate ?: history.from)
        val end=minOf(month.atEndOfMonth().toString(),history.through)
        if(from>end) return emptyList()
        var day=LocalDate.parse(from)
        return buildList {
            while(day.toString()<=end) {
                val date=day.toString(); val row=points[date]
                val foreign=row?.kind==FundKind.QDII || h.name.contains("QDII",true) || h.name.contains("海外")
                val money=row?.kind==FundKind.MONEY || history.points.any { it.kind==FundKind.MONEY }
                val fees=p.trades.filter { it.holdingId==h.id && it.date==date }.fold(BigDecimal.ZERO) { a,t->a+t.fee }
                if(row!=null || money || foreign || calendar.status(date)!=TradingDay.CLOSED || fees.signum()!=0) {
                    val opening=HoldingTimeline.opening(p,h,date)
                    var note=opening.reason; var value: BigDecimal?=null
                    if(opening.shares!=null) {
                        value=when {
                            opening.shares.signum()==0 -> BigDecimal.ZERO
                            row==null || !row.final -> { note="等待对应日期的正式数据"; null }
                            row.kind==FundKind.MONEY && row.incomePerTenThousand!=null -> opening.shares*row.incomePerTenThousand.movePointLeft(4)
                            !calendar.oneSession(row.referenceDate,date,foreign) -> { note="缺少相邻估值日数据，不能分配跨日收益"; null }
                            row.previousNav==null -> { note="缺少前一估值日净值"; null }
                            row.corporateAction && row.change==null -> { note="存在分红或折算，待核对收益率"; null }
                            !row.corporateAction -> opening.shares*(row.nav-row.previousNav)
                            row.change!=null && row.change>BigDecimal("-100") -> { note="分红/折算日按公布涨幅计算，可能存在舍入差异"; opening.shares*row.previousNav*row.change.movePointLeft(2) }
                            else -> null
                        }
                    }
                    add(IncomeEntry(h.id,h.code,h.name,h.groupId,date,row?.currency ?: history.points.firstOrNull()?.currency ?: "CNY",
                        if(value==null) IncomeKind.UNKNOWN else IncomeKind.CONFIRMED,value?.subtract(fees)?.setScale(2,RoundingMode.HALF_UP),opening.shares,
                        row?.source ?: "等待公布",row?.asOf ?: date,note,p.trades.any { it.holdingId==h.id && it.date==date && it.kind=="CALIBRATE" }))
                }
                day=day.plusDays(1)
            }
        }
    }
    fun merge(p: Portfolio,rows: List<IncomeEntry>): Portfolio {
        val result=p.incomes.associateBy { it.key }.toMutableMap()
        val rank=mapOf(IncomeKind.UNKNOWN to 0,IncomeKind.PARTIAL to 1,IncomeKind.ESTIMATE to 2,IncomeKind.CONFIRMED to 3)
        for(row in rows) {
            val old=result[row.key]
            if(old==null || row.invalidated || rank.getValue(row.kind)>=rank.getValue(old.kind)) result[row.key]=row
        }
        return p.copy(incomes=result.values.sortedWith(compareBy<IncomeEntry> { it.date }.thenBy { it.code }.thenBy { it.currency }))
    }
    fun summary(rows: List<IncomeEntry>,from: String,through: String,currency: String): IncomeSummary {
        val selected=rows.filter { it.date in from..through && it.currency==currency }
        fun sum(kind: IncomeKind)=selected.filter { it.kind==kind }.fold(BigDecimal.ZERO) { a,r->a+(r.amount ?: BigDecimal.ZERO) }
        return IncomeSummary(sum(IncomeKind.CONFIRMED),sum(IncomeKind.ESTIMATE),sum(IncomeKind.PARTIAL),selected.count { it.kind==IncomeKind.UNKNOWN || it.amount==null },selected.size,selected.count { it.kind in listOf(IncomeKind.ESTIMATE,IncomeKind.PARTIAL) })
    }
}
