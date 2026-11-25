package com.myfunds.android

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w480dp-h800dp-mdpi")
class DcaOverviewUiTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun allOverviewTextFitsAndOpeningCardListsOnlyActivePlans() {
        for (size in DisplaySize.entries) for (missingPrice in listOf(false, true)) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val p = Portfolio(holdings = listOf(
            Holding("active1", "000001", "测试指数金额定投A", dcaAmount = BigDecimal.TEN, dcaLastDate = date),
            Holding("active2", "000002", "测试指数份额定投C", groupId = "other", dcaShares = BigDecimal("2.5"), dcaLastDate = date),
            Holding("inactive", "000003", "未定投基金")), groups = listOf(Group("default", "默认分组"), Group("other", "另一个分组")), settings = AppSettings(displaySize = size))
        MainActivity::class.java.getDeclaredField("portfolio").apply { isAccessible = true }.set(activity, p)
        MainActivity::class.java.getDeclaredField("quotes").apply { isAccessible = true }.set(activity,
            if (missingPrice) emptyMap<String, FundQuote>() else mapOf("000002" to FundQuote("000002", "测试指数份额定投C", BigDecimal("2.00"), date)))
        MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 480, 800)
        val title = views(root).filterIsInstance<TextView>().first { it.text.toString() == "今日计划投入" }
        val card = title.parent as ViewGroup
        for (text in views(card).filterIsInstance<TextView>()) {
            assertTrue("Overview text clipped: ${text.text}", text.bottom <= card.height - card.paddingBottom)
        }
        assertTrue("DCA overview should be actionable", card.isClickable)
        card.performClick(); shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val labels = (views(dialog.window!!.decorView)+(0 until dialog.listView.adapter.count).flatMap { views(dialog.listView.adapter.getView(it,null,dialog.listView)) }).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.any { it.contains("测试指数金额定投A") })
        assertTrue(labels.any { it.contains("测试指数份额定投C") })
        assertFalse(labels.any { it.contains("未定投基金") })
        assertTrue(labels.any { it.contains(if (missingPrice) "等待净值" else "15.00") })
        val item = dialog.listView.adapter.getView(0, null, dialog.listView)
        assertTrue("DCA entries need a structured card", item is ViewGroup)
        val edit = views(item).filterIsInstance<Button>().firstOrNull { it.text.toString() == "编辑计划" }
        assertNotNull("Each DCA card needs an explicit editing action", edit)
        edit!!.performClick(); shadowOf(Looper.getMainLooper()).idle()
        val editor = ShadowAlertDialog.getLatestAlertDialog()
        val fields = views(editor.window!!.decorView).filterIsInstance<android.widget.EditText>()
        assertTrue(fields.any { it.hint.toString().startsWith("每次定投金额") && it.text.toString() == "10" })
        assertFalse("定投不应支持设置份额", fields.any { it.hint.toString().contains("份额") })
        editor.dismiss(); controller.pause().stop().destroy()
        }
    }
}
