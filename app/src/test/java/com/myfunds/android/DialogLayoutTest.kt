package com.myfunds.android

import com.myfunds.core.DisplaySize
import android.app.Activity
import android.content.DialogInterface
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w320dp-h480dp-mdpi")
class DialogLayoutTest {
    @Test fun longContentLeavesCompleteActionButtonsVisibleInBothThemes() {
        for (dark in listOf(false, true)) for (threeButtons in listOf(false, true)) for (size in DisplaySize.entries) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            val ui = Ui(activity, dark, displaySize = size)
            val content = ScrollView(activity).apply {
                addView(ui.box().apply { repeat(60) { addView(ui.text("基金持仓与定投明细 $it", 18f)) } })
            }
            val dialog = ui.dialog().setTitle("基金持仓")
                .setView(content).setNegativeButton("关闭", null).setPositiveButton("管理持仓", null).apply {
                    if (threeButtons) setNeutralButton("取消定投计划", null)
                }.create()
            var clicks = 0
            dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { clicks++ } }
            dialog.show()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val decor = dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            for (id in listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE) + if (threeButtons) listOf(DialogInterface.BUTTON_NEUTRAL) else emptyList()) {
                val button = dialog.getButton(id)
                val visible = Rect()
                assertTrue("Button missing from viewport: ${button.width}x${button.height}, parent=${(button.parent as View).width}x${(button.parent as View).height}", button.getLocalVisibleRect(visible))
                assertEquals("Action button clipped at top", 0, visible.top)
                assertEquals("Action button clipped at bottom", button.height, visible.bottom)
                assertTrue("Action touch target was squeezed: ${button.height}", button.height >= ui.dp(44))
                var child: View = button
                var parent = button.parent
                while (parent is ViewGroup && parent !== decor) {
                    assertTrue("Action exceeds parent top", child.top >= parent.paddingTop)
                    assertTrue("Action exceeds parent bottom", child.bottom <= parent.height - parent.paddingBottom)
                    child = parent; parent = parent.parent
                }
            }
            dialog.getButton(-1).performClick()
            assertEquals(1, clicks)
            dialog.dismiss(); controller.pause().stop().destroy()
        }
    }
}
