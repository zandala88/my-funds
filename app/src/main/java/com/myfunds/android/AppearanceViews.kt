package com.myfunds.android

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import com.myfunds.core.DisplaySize

private fun Ui.tint(color: Int) = Color.argb(if (dark) 30 else 18, Color.red(color), Color.green(color), Color.blue(color))
private fun Ui.choiceRow(label: String, selected: Boolean, preview: View): View = box().apply {
    setPadding(dp(20), dp(5), dp(20), dp(5))
    addView(bentoCard(18, if (selected) if (dark) Color.parseColor("#252642") else Color.parseColor("#F3F1FF") else card).apply {
        round(this, if (selected) if (dark) Color.parseColor("#252642") else Color.parseColor("#F3F1FF") else card,
            18, if (selected) accent else border, if (selected) 2 else 1)
        addView(box(false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(RadioButton(context).apply {
                isChecked = selected; buttonTintList = ColorStateList.valueOf(accent)
                isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
        })
        addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }, LinearLayout.LayoutParams(-1, -2))
}

fun Ui.colorPreview(redUp: Boolean): View = box(false).apply {
    for ((index, up) in listOf(true, false).withIndex()) {
        val color = if (up == redUp) rose else green
        val style = if (redUp) "红涨绿跌" else "绿涨红跌"
        addView(box().apply {
            setPadding(dp(12), dp(9), dp(12), dp(9)); round(this, tint(color), 12, strokeWidth = 0)
            addView(text(if (up) "↑ 上涨" else "↓ 下跌", 11f, color))
            addView(text(if (up) "+1.28%" else "−0.86%", 18f, color, true).apply {
                contentDescription = "$style：${if (up) "上涨" else "下跌"}示例"
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(8) })
    }
}

fun Ui.sizePreview(size: DisplaySize): View {
    val preview = forDisplaySize(size)
    return preview.box().apply {
        setPadding(preview.dp(12), preview.dp(9), preview.dp(12), preview.dp(9))
        preview.round(this, soft, 12, strokeWidth = 0)
        addView(preview.text("持仓总额", 10f, muted))
        addView(preview.text("1,234.56", 20f, bold = true).apply { contentDescription = "字号样例金额" })
    }
}

fun Ui.preferenceCard(title: String, value: String, preview: View, action: () -> Unit): View = bentoCard(16).apply {
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    addView(box(false).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(title, 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("$value ›", 12f, accent))
    })
    addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    contentDescription = "$title：$value"; isFocusable = true
    background = RippleDrawable(ColorStateList.valueOf(soft), shape(card, 16, border, 1), shape(Color.WHITE, 16))
    setOnClickListener { action() }
}

private fun Ui.showPreviewChoices(title: String, labels: List<String>, selected: Int, preview: (Int) -> View, choose: (Int) -> Unit) {
    val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = choiceRow(labels[position], position == selected, preview(position))
    }
    val heading = box().apply {
        setPadding(dp(24), dp(20), dp(24), dp(12))
        addView(text(title, 20f, bold = true))
    }
    val dialog = dialog().setCustomTitle(heading).setAdapter(adapter) { _, index -> choose(index) }.setNegativeButton("取消", null).create()
    dialog.setOnShowListener { dialog.listView.divider = null; dialog.listView.setPadding(0, dp(4), 0, dp(4)) }
    dialog.show()
}

fun Ui.showColorChoices(redUp: Boolean, choose: (Boolean) -> Unit) = showPreviewChoices("涨跌配色", listOf("红涨绿跌", "绿涨红跌"), if (redUp) 0 else 1,
    { colorPreview(it == 0) }, { choose(it == 0) })
fun Ui.showSizeChoices(size: DisplaySize, choose: (DisplaySize) -> Unit) = showPreviewChoices("字体与界面大小", listOf("较小", "标准", "较大"), size.ordinal,
    { sizePreview(DisplaySize.entries[it]) }, { choose(DisplaySize.entries[it]) })
