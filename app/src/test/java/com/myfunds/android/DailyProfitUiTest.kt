package com.myfunds.android

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.myfunds.core.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w480dp-h800dp-mdpi")
class DailyProfitUiTest {
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val h = Holding("h", "000001", "日期测试基金", shares = BigDecimal("100"), cost = BigDecimal.ONE)
    private fun quote(date: LocalDate) = FundQuote(h.code, h.name, BigDecimal("1.02"), date.toString(), change = BigDecimal("2"))
    private fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    private fun withPage(holdings: List<Holding> = listOf(h), quotes: List<FundQuote>, check: (MainActivity, List<String>) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            MainActivity::class.java.getDeclaredField("portfolio").apply { isAccessible = true }
                .set(activity, Portfolio(holdings = holdings))
            MainActivity::class.java.getDeclaredField("quotes").apply { isAccessible = true }
                .set(activity, quotes.associateBy { it.code })
            MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
            check(activity, texts(activity.window.decorView))
        } finally { controller.pause().stop().destroy() }
    }
    private fun ledgerTexts(activity: MainActivity): List<String> {
        MainActivity::class.java.getDeclaredMethod("showHoldingLedger", Holding::class.java)
            .apply { isAccessible = true }.invoke(activity, h)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        return try { texts(dialog.window!!.decorView) } finally { dialog.dismiss() }
    }

    @Test fun yesterdayNavIsDatedInListAndLedgerAndExcludedFromToday() {
        withPage(quotes = listOf(quote(today.minusDays(1)))) { activity, labels ->
            assertTrue(labels.toString(), labels.any { it == "昨日收益 " })
            assertTrue(labels.any { it == "今日收益 暂无数据" })
            assertTrue(labels.any { it == "净值日期 ${today.minusDays(1)}" })
            val ledger = ledgerTexts(activity)
            assertTrue(ledger.any { it == "昨日收益" })
            assertFalse(ledger.any { it == "今日收益" })
        }
    }
    @Test fun olderNavShowsActualDateRatherThanYesterday() {
        withPage(quotes = listOf(quote(today.minusDays(3)))) { _, labels ->
            assertTrue(labels.toString(), labels.any { it == "${today.minusDays(3)} 收益 " })
        }
    }
    @Test fun zeroChangeEstimateStaysTodayAndShowsEstimateTime() {
        val q = quote(today.minusDays(1)).copy(estimate = BigDecimal("1.0200"), estimateChange = BigDecimal.ZERO,
            estimateTime = "$today 14:00")
        withPage(quotes = listOf(q)) { activity, labels ->
            assertTrue(labels.toString(), labels.any { it == "今日估算 0.00" })
            assertTrue(labels.any { it == "估值时间 $today 14:00" })
            assertTrue(ledgerTexts(activity).any { it == "今日估算收益" })
        }
    }
    @Test fun mixedDatesOnlySumTodayAndIdentifyPartialCoverage() {
        val other = h.copy(id = "other", code = "000002", name = "旧日期基金")
        withPage(listOf(h, other), listOf(quote(today), quote(today.minusDays(1)).copy(code = other.code, nav = BigDecimal("1.50"), change = BigDecimal("50")))) { _, labels ->
            assertTrue(labels.toString(), labels.any { it == "今日收益（部分） +2.00" })
            assertTrue(labels.any { it.startsWith("今日数据 1/2 只") })
        }
    }
    @Test fun missingCurrentQuoteDoesNotCountAsZeroOrCompleteCoverage() {
        val other = h.copy(id = "other", code = "000002")
        withPage(listOf(h, other), listOf(quote(today))) { _, labels ->
            assertTrue(labels.toString(), labels.any { it == "今日收益（部分） +2.00" })
        }
        withPage(quotes = emptyList()) { _, labels ->
            assertTrue(labels.any { it == "今日收益 暂无数据" })
        }
    }
    @Test fun publishedTodayNavSupersedesEstimateAndShowsNavDate() {
        withPage(quotes = listOf(quote(today).copy(estimate = BigDecimal("1.08"), estimateTime = "$today 15:00"))) { activity, labels ->
            assertTrue(labels.any { it == "今日收益 +2.00" })
            assertTrue(labels.toString(), labels.any { it == "净值日期 $today" })
            assertTrue(ledgerTexts(activity).any { it == "今日收益" })
        }
    }
    @Test fun futureNavNeverBecomesTodayProfit() {
        withPage(quotes = listOf(quote(today.plusDays(1)))) { _, labels ->
            assertTrue(labels.toString(), labels.any { it == "今日收益 暂无数据" })
            assertFalse(labels.any { it == "今日 +2.00" })
        }
    }
    @Test fun watchOnlyFundsDoNotMakeCurrentCoverageLookComplete() {
        withPage(listOf(h, h.copy(id = "watch", code = "000002", shares = BigDecimal.ZERO)), listOf(quote(today.minusDays(1)))) { _, labels ->
            assertTrue(labels.toString(), labels.any { it == "今日收益 暂无数据" })
        }
    }
    @Test fun datedProfitAndPartialOverviewFitAvailableWidth() {
        val other = h.copy(id = "other", code = "000002")
        withPage(listOf(h, other), listOf(quote(today), quote(today.minusDays(3)).copy(code = other.code))) { activity, _ ->
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 480, 800)
            fun check(view: View) {
                if (view is TextView && (view.text.startsWith("今日收益") || view.text.startsWith("${today.minusDays(3)} 收益"))) {
                    val parent = view.parent as ViewGroup
                    for (i in 0 until parent.childCount) {
                        val sibling = parent.getChildAt(i) as? TextView ?: continue
                        assertTrue("Profit clipped: ${sibling.text}", sibling.right <= parent.width - parent.paddingRight)
                        assertTrue("Profit has no width: ${sibling.text}", sibling.width > sibling.totalPaddingLeft + sibling.totalPaddingRight)
                        val layout = sibling.layout
                        if (layout != null) for (line in 0 until layout.lineCount) {
                            assertTrue("Profit line clipped: ${sibling.text}", layout.getLineWidth(line) <= sibling.width - sibling.totalPaddingLeft - sibling.totalPaddingRight + 1)
                        }
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
            }
            check(root)
        }
    }

    @Test fun quoteDateRemainsVisibleWhenDailyChangeIsMissing() {
        withPage(quotes = listOf(quote(today.minusDays(1)).copy(change = null))) { activity, labels ->
            assertTrue(labels.toString(), labels.any { it == "净值日期 ${today.minusDays(1)}" })
            val ledger = ledgerTexts(activity)
            assertTrue(ledger.toString(), ledger.any { it == "净值日期 ${today.minusDays(1)}" })
            assertTrue(ledger.any { it == "暂无数据" })
        }
    }

}
