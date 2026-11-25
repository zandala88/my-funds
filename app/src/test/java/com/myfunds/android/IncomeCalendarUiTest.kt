package com.myfunds.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Spinner
import com.myfunds.core.*
import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28,35])
class IncomeCalendarUiTest {
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun calendarDistinguishesFormalEstimatesMissingAndCurrenciesAndShowsSources() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val r=IncomeEntry("h","000001","测试基金","default","2026-11-24","CNY",IncomeKind.CONFIRMED,BigDecimal("2.00"),BigDecimal("100"),"公布净值","2026-11-24")
        val p=Portfolio(incomes=listOf(r,r.copy(code="000002",kind=IncomeKind.ESTIMATE,amount=BigDecimal("3.00")),r.copy(code="000003",kind=IncomeKind.UNKNOWN,amount=null,note="等待净值"),r.copy(code="000004",currency="USD",amount=BigDecimal("99.00"))))
        IncomeCalendarViews(Ui(activity,false),{p}).show(YearMonth.of(2026,11))
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        val text=views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.joinToString(" ")
        assertTrue(text.contains("已公布 +2.00")); assertTrue(text.contains("估算 +3.00")); assertTrue(text.contains("待补 1")); assertFalse(text.contains("+101.00"))
        views(dialog.window!!.decorView).first { it.contentDescription?.toString()?.startsWith("2026-11-24 ")==true }.performClick()
        val detail=ShadowAlertDialog.getLatestAlertDialog()
        val detailText=views(detail.window!!.decorView).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }
        assertTrue(detailText.contains("公布净值")); assertTrue(detailText.contains("等待净值"))
        detail.dismiss(); dialog.dismiss(); activity.finish()
    }
    @Test fun scheduleEditorExposesWeeklyMonthlyFutureDateAndPauseControls() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        var saved: DcaSchedule?=null
        DcaScheduleViews(Ui(activity,false)).show(Holding("h","000001","基金",dcaAmount=BigDecimal.TEN),"2026-11-25","CNY",{ s,_->saved=s },{})
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val all=views(dialog.window!!.decorView)
        val frequency=all.filterIsInstance<Spinner>().first { it.contentDescription=="定投频率" }
        frequency.setSelection(3)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        dialog.getButton(-1).performClick()
        assertNotNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.toString(),saved)
        assertEquals(DcaFrequency.MONTHLY,saved!!.frequency)
        assertEquals("2026-11-25",saved!!.startDate)
        assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("暂停计划") })
        activity.finish()
    }
}
