package com.myfunds.core

import org.junit.Assert.*
import org.junit.Test

class TradingCalendarTest {
    @Test fun holidaysAndMakeupWorkWeekendsAreClosed() {
        val c = TradingCalendar.builtin()
        for (day in listOf("2026-01-02","2026-02-16","2026-09-25","2026-10-01","2026-01-04","2026-10-10")) assertEquals(day, TradingDay.CLOSED, c.status(day))
        assertEquals(TradingDay.OPEN, c.status("2026-11-25"))
        assertEquals("2026-09-28", c.nextOpen("2026-09-25"))
        assertEquals("2026-10-08", c.nextOpen("2026-10-01"))
    }
    @Test fun unknownYearDoesNotPretendToBeOpen() {
        assertEquals(TradingDay.UNKNOWN, TradingCalendar.builtin().status("2027-01-04"))
        assertNull(TradingCalendar.builtin().nextOpen("2027-01-01"))
    }
    @Test fun officialAnnualPageRequiresAllHolidaySections() {
        val page = """<h2>2026年休市安排</h2><p>元旦：1月1日（星期四）至1月3日（星期六）休市。春节：2月15日至2月23日休市。清明节：4月4日至4月6日休市。劳动节：5月1日至5月5日休市。端午节：6月19日至6月21日休市。中秋节：9月25日至9月27日休市。国庆节：10月1日至10月7日休市。</p>"""
        val c = TradingCalendar.parseSse(page)
        assertEquals(TradingDay.CLOSED, c.status("2026-10-07"))
        assertEquals(TradingDay.OPEN, c.status("2026-10-08"))
        assertThrows(IllegalArgumentException::class.java) { TradingCalendar.parseSse("2027年休市安排 元旦：1月1日休市") }
        assertEquals(c, TradingCalendar.decode(c.encode()))
    }
}
