package com.myfunds.core

import java.math.BigDecimal

/** Budget for one execution day across all plans, independent of catch-up ledger entries. */
data class DailyDcaBudget(val knownAmount: BigDecimal, val plans: Int, val missingPrices: Int) {
    val total: BigDecimal? get() = knownAmount.takeIf { missingPrices == 0 }
}
object DcaBudget {
    fun due(p: Portfolio,day: String,calendar: TradingCalendar): List<DcaExecution> {
        val preview=p.holdings.flatMap { h->DcaSchedules.occurrences(h,java.time.LocalDate.parse(day).minusDays(35).toString(),day,calendar) }.filter { it.executionDate==day }
        val known=p.executions.filter { it.executionDate==day }
        return (preview+known).associateBy { it.id }.values.filter { it.status !in setOf(DcaStatus.SKIPPED,DcaStatus.CANCELLED) }.toList()
    }
    fun onDate(p: Portfolio,quotes: Map<String,FundQuote>,day: String,calendar: TradingCalendar): Map<String,DailyDcaBudget> = due(p,day,calendar).groupBy { it.currency }.mapValues { (_,events)->
        var amount=BigDecimal.ZERO; var missing=0
        events.forEach { e->
            val h=p.holdings.firstOrNull { it.id==e.holdingId }
            val n=e.amount ?: h?.dcaAmount
            if(h?.dcaShares?.signum()==1 && e.status!=DcaStatus.RECORDED) {
                val price=quotes[h.code]?.takeIf { it.currency==e.currency }?.nav?.takeIf { it.signum()>0 }
                if(price==null) missing++ else amount+=h.dcaShares*price
            } else if(n!=null) amount+=n else missing++
        }
        DailyDcaBudget(amount,events.size,missing)
    }
    fun next(p: Portfolio,h: Holding,day: String,calendar: TradingCalendar): DcaExecution? {
        val finished=p.executions.filter { it.status in setOf(DcaStatus.RECORDED,DcaStatus.SKIPPED,DcaStatus.CANCELLED) }.map { it.id }.toSet()
        return DcaSchedules.occurrences(h,day,java.time.LocalDate.parse(day).plusDays(370).toString(),calendar).firstOrNull { it.status!=DcaStatus.SKIPPED && it.id !in finished }
    }
    fun daily(holdings: List<Holding>, quotes: Map<String, FundQuote>): DailyDcaBudget {
        var amount = BigDecimal.ZERO
        var plans = 0
        var missing = 0
        for (h in holdings) {
            // Matches execution precedence for existing backups containing both fields.
            val shares = h.dcaShares?.takeIf { it > BigDecimal.ZERO }
            val fixedAmount = h.dcaAmount?.takeIf { it > BigDecimal.ZERO }
            if (shares == null && fixedAmount == null) continue
            plans++
            if (shares != null) {
                val nav = quotes[h.code]?.nav?.takeIf { it > BigDecimal.ZERO }
                if (nav == null) missing++ else amount += shares * nav
            } else amount += fixedAmount!!
        }
        return DailyDcaBudget(amount, plans, missing)
    }
}
