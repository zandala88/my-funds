package com.myfunds.android

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class ColorPreferenceUiTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun choosingGreenUpPersistsAndKeepsSettingsVisibleWhenThemeChanges() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        fun button(prefix: String) = views(activity.window.decorView).filterIsInstance<Button>().first { it.text.startsWith(prefix) }
        assertFalse(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() in listOf("自选", "设置") })
        views(activity.window.decorView).first { it.contentDescription == "设置" }.performClick()
        button("涨跌配色：").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(button("涨跌配色：").text.contains("绿涨红跌"))
        LocalStore(activity).use { assertFalse(it.load().settings.redUp) }
        val dark = views(activity.window.decorView).filterIsInstance<Switch>().first { it.text.toString() == "深色外观" }
        dark.performClick(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(button("涨跌配色：").text.contains("绿涨红跌"))
        LocalStore(activity).use { assertTrue(it.load().settings.darkMode); assertFalse(it.load().settings.redUp) }
        for (darkMode in listOf(false, true)) {
            val ui = Ui(activity, darkMode, false)
            assertEquals(ui.green, ui.changeColor(BigDecimal.ONE))
            assertEquals(ui.rose, ui.changeColor(BigDecimal.ONE.negate()))
            assertEquals(ui.muted, ui.changeColor(BigDecimal.ZERO))
        }
        controller.pause().stop().destroy()
    }
}
