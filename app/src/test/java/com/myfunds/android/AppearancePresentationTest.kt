package com.myfunds.android

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w360dp-h800dp-mdpi")
class AppearancePresentationTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun control(root: View, prefix: String) = views(root).firstOrNull { it.contentDescription?.startsWith(prefix) == true }
        ?: views(root).filterIsInstance<Button>().first { it.text.startsWith(prefix) }
    @Test fun headerHasSmallGlyphsAndSeparateTouchTargets() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val root = controller.get().window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 800)
        val refresh = views(root).filterIsInstance<ImageButton>().first { it.contentDescription == "刷新" }
        val settings = views(root).filterIsInstance<ImageButton>().first { it.contentDescription == "设置" }
        assertTrue("Header icon glyph is oversized", refresh.width - refresh.paddingLeft - refresh.paddingRight <= 22)
        assertTrue("Header actions need breathing room", settings.left - refresh.right >= 8)
        assertTrue(refresh.width >= 44 && settings.width >= 44)
        controller.pause().stop().destroy()
    }
    @Test fun colorChoicesContainActualRedAndGreenExamples() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        control(activity.window.decorView, "设置").performClick()
        control(activity.window.decorView, "涨跌配色：").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idle()
        val samples = views(dialog.window!!.decorView).filterIsInstance<TextView>()
        val positiveRed = samples.firstOrNull { it.contentDescription == "红涨绿跌：上涨示例" }
        val positiveGreen = samples.firstOrNull { it.contentDescription == "绿涨红跌：上涨示例" }
        assertNotNull("Color choices need colored examples", positiveRed)
        assertNotNull(positiveGreen)
        val ui = Ui(activity, false)
        assertEquals(ui.rose, positiveRed!!.currentTextColor)
        assertEquals(ui.green, positiveGreen!!.currentTextColor)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
    @Test fun sizeChoicesShowDifferentActualSampleSizes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        control(activity.window.decorView, "设置").performClick()
        control(activity.window.decorView, "字体与界面大小").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val previews = (0 until dialog.listView.adapter.count).map { index ->
            val item = dialog.listView.adapter.getView(index, null, dialog.listView)
            views(item).filterIsInstance<TextView>().firstOrNull { it.contentDescription == "字号样例金额" }
        }
        assertTrue("Size choices need visual samples", previews.all { it != null })
        assertTrue(previews[0]!!.textSize < previews[1]!!.textSize && previews[1]!!.textSize < previews[2]!!.textSize)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
}
