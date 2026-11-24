package com.myfunds.android

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
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
@Config(sdk = [28, 35], qualifiers = "w320dp-h640dp-mdpi")
class ChoiceIndicatorLayoutTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun choiceIndicatorsHaveRoomForWholeSystemDrawableAtEverySize() {
        for (size in DisplaySize.entries) for (dark in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val ui = Ui(controller.get(), dark, displaySize = size)
            for (fontChoice in listOf(false, true)) {
                if (fontChoice) ui.showSizeChoices(size) {} else ui.showColorChoices(true) {}
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                shadowOf(Looper.getMainLooper()).idle()
                for (i in 0 until dialog.listView.adapter.count) {
                    val row = dialog.listView.adapter.getView(i, null, dialog.listView)
                    row.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                    val radio = views(row).filterIsInstance<RadioButton>().single()
                    val drawable = radio.buttonDrawable!!
                    assertTrue("Radio drawable clipped horizontally: ${radio.width} < ${drawable.intrinsicWidth}", radio.width - radio.paddingLeft - radio.paddingRight >= drawable.intrinsicWidth)
                    assertTrue(radio.height - radio.paddingTop - radio.paddingBottom >= drawable.intrinsicHeight)
                }
                dialog.dismiss()
            }
            controller.pause().stop().destroy()
        }
    }
}
