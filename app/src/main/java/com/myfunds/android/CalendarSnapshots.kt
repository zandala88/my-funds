package com.myfunds.android

import com.myfunds.core.*
import java.math.BigDecimal
import java.math.RoundingMode

internal object CalendarSnapshots {
    fun afterEdit(before: Portfolio,next: Portfolio): Portfolio {
        val affected=mutableMapOf<String,String>()
        next.holdings.forEach { h->
            val old=before.holdings.firstOrNull { it.id==h.id }
            if(old!=null && (old.confirmedDate!=h.confirmedDate || old.initialShares!=h.initialShares)) affected[h.code]=minOf(old.confirmedDate ?: "0000-01-01",h.confirmedDate ?: "0000-01-01")
            if(old==null && before.incomes.any { it.code==h.code } && h.confirmedDate!=null) affected[h.code]=requireNotNull(h.confirmedDate)
        }
        val oldTrades=before.trades.associateBy { it.id }
        next.trades.filter { oldTrades[it.id]!=it }.forEach { t->
            val h=next.holdings.firstOrNull { it.id==t.holdingId } ?: return@forEach
            affected[h.code]=minOf(affected[h.code] ?: t.date,t.date)
        }
        return next.copy(incomes=next.incomes.map { r->
            if(affected[r.code]?.let { r.date>=it }==true) r.copy(kind=IncomeKind.UNKNOWN,amount=null,shares=null,invalidated=true,note="持仓基准或操作记录已更新，等待按新账本重算") else r
        })
    }

    fun display(p: Portfolio,h: Holding,q: FundQuote?,today: String): DisplayedDailyProfit? {
        val raw=DailyProfitPresentation.forHolding(h,q,today) ?: return null
        val opening=HoldingTimeline.opening(p,h,raw.date).shares
        if(opening==null) return raw.copy(complete=false)
        val result=DailyProfitPresentation.forHolding(h.copy(shares=opening),q,today) ?: return null
        val fees=p.trades.filter { it.holdingId==h.id && it.date==result.date }.fold(BigDecimal.ZERO) { a,t->a+t.fee }
        return result.copy(amount=(result.amount-fees).setScale(2,RoundingMode.HALF_UP))
    }
    fun capture(p: Portfolio,quotes: Map<String,FundQuote>,today: String): Portfolio {
        val entries=p.holdings.mapNotNull { h->
            val q=quotes[h.code] ?: return@mapNotNull null
            val shown=display(p,h,q,today) ?: return@mapNotNull null
            if(!shown.estimated && q.kind !in listOf(FundKind.ETF,FundKind.REIT)) return@mapNotNull null
            val opening=HoldingTimeline.opening(p,h,shown.date)
            val kind=when { opening.shares==null->IncomeKind.UNKNOWN; !shown.complete->IncomeKind.PARTIAL; shown.estimated->IncomeKind.ESTIMATE; else->IncomeKind.CONFIRMED }
            // Listed intraday prices remain estimates until a final unadjusted close arrives from history.
            val listed=q.kind in listOf(FundKind.ETF,FundKind.REIT)
            val adjusted=if(listed && kind==IncomeKind.CONFIRMED) IncomeKind.ESTIMATE else kind
            IncomeEntry(h.id,h.code,h.name,h.groupId,shown.date,q.currency,adjusted,if(kind==IncomeKind.UNKNOWN) null else shown.amount,opening.shares,
                shown.model?.source ?: if(shown.estimated) "盘中净值估算" else "公布净值",shown.asOf,
                if(opening.shares==null) opening.reason else shown.provenance() ?: if(shown.accountingOnly) "净值口径；场内价格以历史收盘价为准" else "",
                p.trades.any { it.holdingId==h.id && it.date==shown.date && it.kind=="CALIBRATE" })
        }
        return IncomeCalendar.merge(p,entries)
    }
}
