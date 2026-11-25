package com.myfunds.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class DcaFrequency { TRADING_DAY, DAILY, WEEKLY, MONTHLY }
enum class HolidayPolicy { NEXT, SKIP, KEEP }
enum class DcaStatus { SCHEDULED, WAITING_CALENDAR, WAITING_NAV, WAITING_PREVIOUS, REVIEW, RECORDED, SKIPPED, CANCELLED }
data class DcaSchedule(val frequency: DcaFrequency,val startDate: String,val weekDay: Int=1,val monthDay: Int=1,val endDate: String?=null,
    val holidayPolicy: HolidayPolicy=HolidayPolicy.NEXT,val paused: Boolean=false,val currency: String="CNY",val revision: String=UUID.randomUUID().toString(),val activeFrom: String?=null) {
    fun validate() {
        LocalDate.parse(startDate); activeFrom?.let { LocalDate.parse(it) }; endDate?.let { LocalDate.parse(it); require(it>=startDate) { "结束日期不能早于开始日期" } }
        require(weekDay in 1..7 && monthDay in 1..31 && currency.matches(Regex("[A-Z]{3}")) && revision.length in 1..100)
    }
}
data class DcaExecution(val id: String,val holdingId: String,val code: String,val name: String,val revision: String,val plannedDate: String,
    val executionDate: String?,val status: DcaStatus,val currency: String="CNY",val amount: BigDecimal?=null,val shares: BigDecimal?=null,
    val price: BigDecimal?=null,val tradeId: String?=null,val note: String="")
object DcaSchedules {
    fun enabled(h: Holding)=(h.dcaShares?.signum() ?: 0)>0 || (h.dcaAmount?.signum() ?: 0)>0
    fun schedule(h: Holding,today: String): DcaSchedule = h.dcaSchedule ?: DcaSchedule(
        if(h.dcaWeekdaysOnly) DcaFrequency.TRADING_DAY else DcaFrequency.DAILY,h.confirmedDate ?: h.dcaLastDate ?: today,
        holidayPolicy=if(h.dcaWeekdaysOnly) HolidayPolicy.NEXT else HolidayPolicy.KEEP,revision="legacy")
    fun occurrences(h: Holding,from: String,through: String,calendar: TradingCalendar): List<DcaExecution> {
        if(!enabled(h) || from>through) return emptyList()
        val s=schedule(h,from); s.validate(); if(s.paused) return emptyList()
        val start=maxOf(from,s.startDate,s.activeFrom ?: s.startDate); val end=minOf(through,s.endDate ?: through)
        if(start>end) return emptyList()
        require(ChronoUnit.DAYS.between(LocalDate.parse(start),LocalDate.parse(end))<=4000) { "请分批计算定投日期" }
        var date=LocalDate.parse(start)
        return buildList {
            while(date.toString()<=end) {
                val matches=when(s.frequency) { DcaFrequency.WEEKLY -> date.dayOfWeek.value==s.weekDay; DcaFrequency.MONTHLY -> date.dayOfMonth==minOf(s.monthDay,date.lengthOfMonth()); else -> true }
                if(matches) {
                    val planned=date.toString(); val state=calendar.status(planned)
                    if(s.frequency!=DcaFrequency.TRADING_DAY || state!=TradingDay.CLOSED) {
                        val keep=s.frequency==DcaFrequency.DAILY && s.holidayPolicy==HolidayPolicy.KEEP || s.holidayPolicy==HolidayPolicy.KEEP && s.frequency!=DcaFrequency.TRADING_DAY
                        val execution=when { keep || state==TradingDay.OPEN -> planned; state==TradingDay.UNKNOWN -> null; s.holidayPolicy==HolidayPolicy.NEXT -> calendar.nextOpen(planned); else -> null }
                        val status=when { execution!=null -> DcaStatus.SCHEDULED; state==TradingDay.CLOSED && s.holidayPolicy==HolidayPolicy.SKIP -> DcaStatus.SKIPPED; else -> DcaStatus.WAITING_CALENDAR }
                        val id=UUID.nameUUIDFromBytes("${h.id}:${s.revision}:$planned".toByteArray(Charsets.UTF_8)).toString()
                        add(DcaExecution(id,h.id,h.code,h.name,s.revision,planned,execution,status,s.currency,h.dcaAmount,note=if(status==DcaStatus.SKIPPED) "休市跳过" else ""))
                    }
                }
                date=date.plusDays(1)
            }
        }
    }
    fun describe(s: DcaSchedule)=when(s.frequency) { DcaFrequency.TRADING_DAY -> "每个交易日"; DcaFrequency.DAILY -> "每天"; DcaFrequency.WEEKLY -> "每周${listOf("一","二","三","四","五","六","日")[s.weekDay-1]}"; DcaFrequency.MONTHLY -> "每月 ${s.monthDay} 日（月末补齐）" }
}
data class DcaResult(val portfolio: Portfolio)
object DcaEngine {
    private val terminal=setOf(DcaStatus.RECORDED,DcaStatus.SKIPPED,DcaStatus.CANCELLED)
    fun advance(p: Portfolio,today: String,prices: List<DailyNav>,calendar: TradingCalendar): DcaResult {
        LocalDate.parse(today)
        var current=p
        val events=p.executions.associateBy { it.id }.toMutableMap()
        for(original in p.holdings.filter { DcaSchedules.enabled(it) }) {
            val cursor=original.dcaLastDate ?: p.trades.filter { it.holdingId==original.id && it.kind=="DCA" }.maxOfOrNull { it.date }
            val s=DcaSchedules.schedule(original,today).let { if(original.dcaSchedule==null && original.confirmedDate==null && original.dcaLastDate==null && cursor!=null) it.copy(startDate=cursor) else it }
            val from=maxOf(s.startDate,s.activeFrom ?: s.startDate,cursor?.let { LocalDate.parse(it).plusDays(1).toString() } ?: s.startDate)
            var checked=original.dcaLastDate
            if(!s.paused && from<=today) {
                val through=minOf(today,LocalDate.parse(from).plusDays(1999).toString())
                DcaSchedules.occurrences(original,from,through,calendar).forEach { events.putIfAbsent(it.id,it) }; checked=through
            }
            var blocked=false
            for(event in events.values.filter { it.holdingId==original.id && it.status !in terminal }.sortedWith(compareBy<DcaExecution> { it.plannedDate }.thenBy { it.id })) {
                var e=event
                if(e.revision!=s.revision) { events[e.id]=e.copy(status=DcaStatus.CANCELLED,note="计划已变更"); continue }
                if(e.executionDate==null) {
                    val retry=DcaSchedules.occurrences(original.copy(dcaSchedule=s.copy(paused=false,activeFrom=s.startDate)),e.plannedDate,e.plannedDate,calendar).firstOrNull()
                    if(retry!=null) e=e.copy(executionDate=retry.executionDate,status=retry.status)
                    else if(s.frequency==DcaFrequency.TRADING_DAY && calendar.status(e.plannedDate)==TradingDay.CLOSED) e=e.copy(status=DcaStatus.SKIPPED,note="已核实为休市日")
                }
                if(e.status in terminal) { events[e.id]=e; continue }
                val date=e.executionDate
                val existing=current.trades.firstOrNull { it.id==e.id }
                if(existing!=null) { events[e.id]=e.copy(status=DcaStatus.RECORDED,tradeId=existing.id,shares=existing.shares,price=existing.price,amount=existing.price*existing.shares,note="已记入本地账本"); continue }
                if(blocked) { events[e.id]=e.copy(status=DcaStatus.WAITING_PREVIOUS,note="等待更早一期处理"); continue }
                if(date==null) { events[e.id]=e.copy(status=DcaStatus.WAITING_CALENDAR,note="等待交易所年度休市日历"); blocked=true; continue }
                if(date>today) { events[e.id]=e.copy(status=DcaStatus.SCHEDULED,note="已顺延，等待执行日"); continue }
                val conflict=current.trades.any { it.holdingId==original.id && (it.date>date || it.date==date && it.kind in listOf("SELL","CALIBRATE")) } || (original.confirmedDate ?: "")>date
                if(conflict) { events[e.id]=e.copy(status=DcaStatus.REVIEW,note="已有同日卖出/校准或更晚操作，请核对后跳过本期，另行录入实际交易"); blocked=true; continue }
                val price=prices.firstOrNull { it.code==original.code && it.date==date && it.final && it.currency==s.currency && it.nav.signum()>0 }
                if(price==null) { events[e.id]=e.copy(status=DcaStatus.WAITING_NAV,note="等待 $date 正式${if(original.code.startsWith("5") || original.code.startsWith("1")) "价格" else "净值"}（${s.currency}）"); blocked=true; continue }
                val shares=original.dcaShares?.takeIf { it.signum()>0 } ?: original.dcaAmount!!.divide(price.nav,8,RoundingMode.DOWN)
                if(shares.signum()<=0) { events[e.id]=e.copy(status=DcaStatus.REVIEW,note="金额不足以折算最小份额，请修改计划"); blocked=true; continue }
                current=PortfolioOperations.recordDca(current,original.id,shares,price.nav,date,e.id)
                events[e.id]=e.copy(status=DcaStatus.RECORDED,tradeId=e.id,price=price.nav,shares=shares,amount=shares*price.nav,note="${price.source} · 本地模拟记账")
            }
            current=current.copy(holdings=current.holdings.map { if(it.id==original.id) it.copy(dcaLastDate=checked) else it })
        }
        return DcaResult(current.copy(executions=events.values.toList()).validate())
    }
    fun setPaused(p: Portfolio,id: String,paused: Boolean,today: String): Portfolio = p.copy(holdings=p.holdings.map { h -> if(h.id!=id) h else {
        val s=DcaSchedules.schedule(h,today); h.copy(dcaSchedule=s.copy(paused=paused,activeFrom=if(!paused && s.paused) today else s.activeFrom),dcaLastDate=if(!paused && s.paused) LocalDate.parse(today).minusDays(1).toString() else h.dcaLastDate)
    } }).validate()
    fun replacePlan(p: Portfolio,id: String,schedule: DcaSchedule,amount: BigDecimal,today: String): Portfolio {
        validDecimal(amount); require(amount.signum()>0) { "定投金额必须大于零" }
        val already=p.trades.any { it.holdingId==id && it.kind=="DCA" && it.date==today }
        val start=maxOf(schedule.startDate,if(already) LocalDate.parse(today).plusDays(1).toString() else today)
        val revised=schedule.copy(startDate=start,activeFrom=start,revision=if(schedule.revision==p.holdings.first { it.id==id }.dcaSchedule?.revision) UUID.randomUUID().toString() else schedule.revision)
        revised.validate()
        return p.copy(holdings=p.holdings.map { if(it.id==id) it.copy(dcaShares=null,dcaAmount=amount,dcaLastDate=LocalDate.parse(start).minusDays(1).toString(),dcaSchedule=revised) else it },
            executions=p.executions.map { if(it.holdingId==id && it.status !in terminal) it.copy(status=DcaStatus.CANCELLED,note="计划已变更") else it }).validate()
    }
    fun remove(p: Portfolio,id: String)=p.copy(holdings=p.holdings.map { if(it.id==id) it.copy(dcaShares=null,dcaAmount=null,dcaLastDate=null,dcaSchedule=null) else it },executions=p.executions.map { if(it.holdingId==id && it.status !in terminal) it.copy(status=DcaStatus.CANCELLED,note="计划已停止") else it }).validate()
    fun skip(p: Portfolio,id: String)=p.copy(executions=p.executions.map { if(it.id==id && it.status !in terminal) it.copy(status=DcaStatus.SKIPPED,note="手动跳过") else it }).validate()
    fun history(p: Portfolio): List<DcaExecution> {
        val linked=p.executions.mapNotNull { it.tradeId }.toSet()
        return (p.executions+p.trades.filter { it.kind=="DCA" && it.id !in linked }.map { t ->
            val h=p.holdings.first { it.id==t.holdingId }
            DcaExecution(t.id,h.id,h.code,h.name,"legacy",t.date,t.date,DcaStatus.RECORDED,amount=t.price*t.shares,shares=t.shares,price=t.price,tradeId=t.id,note="旧版定投记录，计价依据未记录")
        }).sortedWith(compareByDescending<DcaExecution> { it.plannedDate }.thenBy { it.code })
    }
}
