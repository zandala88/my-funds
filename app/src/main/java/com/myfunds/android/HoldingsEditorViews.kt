package com.myfunds.android

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import com.myfunds.core.Holding

data class HoldingEditorRow(val view: LinearLayout, val check: CheckBox)

fun Ui.holdingEditorRow(h: Holding, index: Int, total: Int, selected: Boolean,
    select: (Boolean) -> Unit, move: (Int) -> Unit, drag: (View) -> Boolean): HoldingEditorRow {
    val row = bentoCard(18).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    val checkbox = CheckBox(context).apply {
        contentDescription = "选择基金：${h.code}"; isChecked = selected
        setPadding(0,0,0,0); background = null; buttonTintList = ColorStateList.valueOf(accent)
        minWidth = touchTarget; minHeight = touchTarget
        setOnCheckedChangeListener { _, value -> select(value) }
    }
    row.addView(box(false).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(checkbox, LinearLayout.LayoutParams(touchTarget, touchTarget))
        addView(box().apply {
            addView(text((if (h.starred) "★ " else "") + h.name, 15f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text(h.code, 11f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconButton(R.drawable.ic_drag_handle, "长按拖动排序：${h.code}") {
            Toast.makeText(context, "长按拖动，或使用上移 / 下移", Toast.LENGTH_SHORT).show()
        }.apply { setOnLongClickListener { drag(row) } }, LinearLayout.LayoutParams(headerActionSize, headerActionSize))
    })
    row.addView(box(false).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text("顺序 ${index + 1}", 11f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        for ((label, delta) in listOf("上移" to -1, "下移" to 1)) {
            addView(button(label) { move(delta) }.apply {
                contentDescription = "$label：${h.code}"; textSize = 12f
                isEnabled = index + delta in 0 until total; alpha = if (isEnabled) 1f else 0.35f
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }, LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(5) })
        }
    }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
    row.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
    row.setOnLongClickListener { drag(row) }
    return HoldingEditorRow(row, checkbox)
}
