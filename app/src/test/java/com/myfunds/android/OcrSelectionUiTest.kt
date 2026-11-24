package com.myfunds.android

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import com.myfunds.core.OcrDraftRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class OcrSelectionUiTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun selectAllAndClearKeepEditsAndSurviveRecreation() {
        val controller = Robolectric.buildActivity(OcrImportActivity::class.java).setup()
        val activity = controller.get()
        val drafts = listOf(OcrDraftRow("测试指数A", "000001", "100", "1.20", "2026-11-20", 1, false, "第1张"),
            OcrDraftRow("测试指数B", "000002", "200", "1.50", "2026-11-20", 1, false, "第2张"))
        OcrImportActivity::class.java.getDeclaredMethod("renderDrafts", List::class.java).apply { isAccessible = true }.invoke(activity, drafts)
        var allViews = views(activity.window.decorView)
        val all = allViews.filterIsInstance<CheckBox>().firstOrNull { it.text.startsWith("全选") }
        assertNotNull("Screenshot review needs a select-all control", all)
        val edit = allViews.filterIsInstance<EditText>().first { it.text.toString() == "100" }
        edit.setText("123.45")
        val boxes = allViews.filterIsInstance<CheckBox>().filter { it.text.startsWith("导入第") }
        all!!.performClick()
        assertTrue(boxes.all { it.isChecked }); assertEquals("123.45", edit.text.toString())
        boxes.first().performClick()
        assertFalse(all.isChecked)
        all.performClick(); assertTrue(boxes.all { it.isChecked })
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val restored = Robolectric.buildActivity(OcrImportActivity::class.java).create(state).start().resume().visible()
        allViews = views(restored.get().window.decorView)
        val restoredAll = allViews.filterIsInstance<CheckBox>().first { it.text.startsWith("全选") }
        assertTrue(restoredAll.isChecked)
        assertTrue(allViews.filterIsInstance<EditText>().any { it.text.toString() == "123.45" })
        restoredAll.performClick()
        assertTrue(allViews.filterIsInstance<CheckBox>().filter { it.text.startsWith("导入第") }.none { it.isChecked })
        LocalStore(restored.get()).use { assertTrue(it.load().holdings.isEmpty()) }
        restored.pause().stop().destroy()
    }
}
