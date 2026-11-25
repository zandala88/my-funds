package com.myfunds.android

import com.myfunds.core.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Dated prices are separate from intraday estimates and survive temporary upstream failures. */
internal class CalendarDataClient(private val fetch: (String)->String,private val read: (String)->String?={null},
    private val write: (String,String)->Unit={_,_->},private val now: ()->Instant={Instant.now()}) {
    private fun <T> optional(block: ()->T): T? = try { block() } catch(e: CancellationException) { throw e } catch(e: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException() } catch(_: Exception) { null }
    fun calendar(previous: TradingCalendar): TradingCalendar {
        val key="exchange-calendar-refresh-v1"
        val recent=optional { java.time.Duration.between(Instant.parse(read(key)),now()).seconds in 0..86400 }==true
        if(recent) return previous
        return optional {
            val fresh=TradingCalendar.parseSse(fetch(TradingCalendar.SOURCE))
            write(key,now().toString()); previous.merged(fresh)
        } ?: previous
    }
    fun history(q: FundQuote,month: YearMonth): NavHistory {
        val clock=LocalDateTime.ofInstant(now(),ZoneId.of("Asia/Shanghai"))
        val from=month.atDay(1).minusDays(10).toString()
        val through=minOf(month.atEndOfMonth().toString(),clock.toLocalDate().toString())
        require(from<=through) { "尚未到该月份" }
        val listed=q.kind in listOf(FundKind.ETF,FundKind.REIT) || EstimationParser.listedCandidate(q.code,q.name)
        val symbol=EstimationParser.symbol(q.code)
        val key="daily-history-v1:${q.code}:${q.currency}:$month:${if(listed) "market" else "nav"}"
        fun parse(raw: String,at: LocalDateTime=clock)=if(listed) NavHistoryParser.market(raw,q.code,requireNotNull(symbol),from,through,at.toString()).let { h->h.copy(points=h.points.map { it.copy(currency=q.currency,kind=q.kind) }) }
            else NavHistoryParser.parse(raw,q.code,from,through,q.currency,q.kind)
        val cached=optional { read(key)?.let(::JSONObject) }
        val prior=cached?.let { optional { parse(it.getString("raw"),LocalDateTime.ofInstant(Instant.parse(it.getString("savedAt")),ZoneId.of("Asia/Shanghai"))) } }
        val freshEnough=cached?.let { optional { java.time.Duration.between(Instant.parse(it.getString("savedAt")),now()).seconds in 0..900 } }==true
        if(prior!=null && freshEnough) return prior
        return optional {
            val url=if(listed) "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$symbol,day,$from,$through,100,"
                else "https://api.fund.eastmoney.com/f10/lsjz?fundCode=${q.code}&pageIndex=1&pageSize=500&startDate=$from&endDate=$through"
            val raw=fetch(url); val result=parse(raw)
            // An incomplete response is usable for its explicit dates, but cannot erase earlier cached dates.
            if(result.complete) write(key,JSONObject().put("savedAt",now().toString()).put("raw",raw).toString())
            result.copy(points=(prior?.points.orEmpty()+result.points).associateBy { it.date }.values.sortedBy { it.date })
        } ?: prior ?: throw IllegalStateException("${q.name} 暂无逐日价格，已保存记录保留")
    }
}
