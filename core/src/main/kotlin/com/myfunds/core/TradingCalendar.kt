package com.myfunds.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class TradingDay { OPEN, CLOSED, UNKNOWN }
data class TradingCalendar(val years: Set<Int>, val closed: Set<String>, val source: String = SOURCE) {
    fun status(day: String): TradingDay {
        val date = LocalDate.parse(day)
        if (date.dayOfWeek in listOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY) || day in closed) return TradingDay.CLOSED
        return if (date.year in years) TradingDay.OPEN else TradingDay.UNKNOWN
    }
    fun nextOpen(day: String): String? {
        var d = LocalDate.parse(day)
        repeat(32) {
            when (status(d.toString())) { TradingDay.OPEN -> return d.toString(); TradingDay.UNKNOWN -> return null; else -> d = d.plusDays(1) }
        }
        return null
    }
    fun oneSession(previous: String?, day: String, foreign: Boolean = false): Boolean = runCatching {
        val start=LocalDate.parse(previous); val end=LocalDate.parse(day)
        if (ChronoUnit.DAYS.between(start,end) !in 1..20) return false
        var d=start.plusDays(1)
        while (d<end) {
            val weekend=d.dayOfWeek in listOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)
            if (!weekend && (foreign || status(d.toString())!=TradingDay.CLOSED)) return false
            d=d.plusDays(1)
        }
        true
    }.getOrDefault(false)
    fun merged(new: TradingCalendar) = TradingCalendar(years+new.years, closed.filterNot { LocalDate.parse(it).year in new.years }.toSet()+new.closed,new.source)
    fun encode() = JSONObject().put("years",JSONArray(years.sorted())).put("closed",JSONArray(closed.sorted())).put("source",source).toString()
    companion object {
        const val SOURCE="https://www.sse.com.cn/disclosure/dealinstruc/closed/"
        fun builtin(): TradingCalendar {
            val ranges=listOf("2026-01-01" to "2026-01-03","2026-02-15" to "2026-02-23","2026-04-04" to "2026-04-06","2026-05-01" to "2026-05-05",
                "2026-06-19" to "2026-06-21","2026-09-25" to "2026-09-27","2026-10-01" to "2026-10-07")
            return TradingCalendar(setOf(2026),ranges.flatMap { (a,b) -> days(a,b) }.toSet())
        }
        private fun days(a: String,b: String): List<String> {
            var d=LocalDate.parse(a); val end=LocalDate.parse(b)
            require(ChronoUnit.DAYS.between(d,end) in 0..31) { "休市区间无效" }
            return buildList { while (d<=end) { add(d.toString()); d=d.plusDays(1) } }
        }
        fun parseSse(raw: String): TradingCalendar {
            require(raw.length<=2*1024*1024)
            val text=raw.replace(Regex("<(script|style)[\\s\\S]*?</\\1>",RegexOption.IGNORE_CASE),"").replace(Regex("<[^>]*>"),"")
                .replace("&nbsp;","").replace('（','(').replace('）',')').replace(Regex("\\s+"),"")
            val heading=Regex("(20[0-9]{2})年休市安排").find(text) ?: throw IllegalArgumentException("未找到年度休市安排")
            val year=heading.groupValues[1].toInt()
            val section=text.substring(heading.range.first).substringBefore("相关公告")
            require(listOf("元旦","春节","清明","劳动","端午","中秋","国庆").all { section.contains(it) }) { "年度休市安排不完整" }
            val pattern=Regex("([0-9]{1,2})月([0-9]{1,2})日(?:\\([^)]*\\))?(?:至([0-9]{1,2})月([0-9]{1,2})日(?:\\([^)]*\\))?)?休市")
            val ranges=pattern.findAll(section).map { m ->
                val start=LocalDate.of(year,m.groupValues[1].toInt(),m.groupValues[2].toInt())
                val end=if (m.groupValues[3].isEmpty()) start else LocalDate.of(year,m.groupValues[3].toInt(),m.groupValues[4].toInt())
                days(start.toString(),end.toString())
            }.toList()
            require(ranges.size>=6) { "年度休市日期不足，保留原日历" }
            return TradingCalendar(setOf(year),ranges.flatten().toSet())
        }
        fun decode(raw: String): TradingCalendar {
            val o=JSONObject(raw); val years=o.getJSONArray("years"); val dates=o.getJSONArray("closed")
            require(years.length() in 1..100 && dates.length()<=3660)
            val yy=(0 until years.length()).map { years.getInt(it).also { y -> require(y in 2000..2100) } }.toSet()
            val dd=(0 until dates.length()).map { dates.getString(it).also { d -> require(LocalDate.parse(d).year in yy) } }.toSet()
            return TradingCalendar(yy,dd,o.optString("source",SOURCE).take(500))
        }
    }
}
