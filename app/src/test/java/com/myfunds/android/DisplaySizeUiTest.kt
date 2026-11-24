package com.myfunds.android

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.myfunds.core.DisplaySize
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
class DisplaySizeUiTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun homeUsesHeaderSettingsInsteadOfBottomTabsAndBackReturnsHome() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() in listOf("自选", "设置") })
        val settings = views(activity.window.decorView).firstOrNull { it.contentDescription == "设置" }
        assertNotNull(settings)
        settings!!.performClick()
        assertTrue(views(activity.window.decorView).any { it.contentDescription == "返回自选" })
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val registration = MainActivity::class.java.getDeclaredField("backRegistration").apply { isAccessible = true }
            (registration.get(activity) as android.window.OnBackInvokedCallback).onBackInvoked()
            assertNull(registration.get(activity))
        } else activity.onBackPressed()
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "我的自选" })
        controller.pause().stop().destroy()
    }
    @Test fun sizeChoiceChangesTypographyAndSpacingAndPersistsAfterRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val settings = views(activity.window.decorView).firstOrNull { it.contentDescription == "设置" }
            ?: views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == "设置" }
        settings.performClick()
        val sizes = mutableListOf<Float>(); val paddings = mutableListOf<Int>()
        for ((index, expected) in DisplaySize.entries.withIndex()) {
            val control = views(activity.window.decorView).firstOrNull { it.contentDescription?.startsWith("字体与界面大小：") == true }
            assertNotNull("Font and layout size setting missing", control)
            control!!.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(dialog.listView.getChildAt(index), index, index.toLong())
            shadowOf(Looper.getMainLooper()).idle()
            val heading = views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "显示与刷新" }
            sizes.add(heading.textSize); paddings.add((heading.parent as View).paddingLeft)
            LocalStore(activity).use { assertEquals(expected, it.load().settings.displaySize) }
        }
        assertTrue(sizes[0] < sizes[1] && sizes[1] < sizes[2])
        assertTrue(paddings[0] < paddings[1] && paddings[1] < paddings[2])
        val saved = Bundle(); controller.saveInstanceState(saved).pause().stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        LocalStore(restored.get()).use { assertEquals(DisplaySize.LARGE, it.load().settings.displaySize) }
        assertTrue(views(restored.get().window.decorView).any { it.contentDescription == "字体与界面大小：较大" })
        restored.pause().stop().destroy()
    }
}
