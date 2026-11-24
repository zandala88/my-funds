package com.myfunds.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import java.math.BigDecimal
import java.math.RoundingMode

class Ui(val context: Context, val dark: Boolean) {
    val background = Color.parseColor(if (dark) "#101C19" else "#F4F6F2")
    val card = Color.parseColor(if (dark) "#1C2B26" else "#FFFFFF")
    val ink = Color.parseColor(if (dark) "#E7F2E9" else "#1C332B")
    val muted = Color.parseColor(if (dark) "#9DAFA4" else "#78877D")
    val accent = Color.parseColor(if (dark) "#B1DFBE" else "#237C66")
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    fun box(vertical: Boolean = true) = LinearLayout(context).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
    fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }
    fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 13f; setTextColor(accent); minWidth = 0
        setOnClickListener { action() }
    }
    fun field(hintText: String, value: String = "", numeric: Boolean = false) = EditText(context).apply {
        hint = hintText; setText(value); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(true)
        if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    fun round(view: android.view.View, color: Int = card, radius: Int = 18) {
        view.background = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    }
    fun panel() = box().apply {
        setPadding(dp(18), dp(15), dp(18), dp(15)); round(this)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    fun amount(n: BigDecimal?, digits: Int = 2): String = n?.setScale(digits, RoundingMode.HALF_UP)?.toPlainString() ?: "—"
    fun signed(n: BigDecimal?, suffix: String = ""): String = n?.let { (if (it > BigDecimal.ZERO) "+" else "") + amount(it) + suffix } ?: "暂无"
    fun changeColor(n: BigDecimal?): Int = when { n == null || n.signum() == 0 -> muted; n.signum() > 0 -> Color.parseColor(if (dark) "#FF9B88" else "#C65C4F"); else -> accent }
    fun stat(parent: LinearLayout, label: String, value: String, color: Int = ink) {
        parent.addView(box().apply { addView(text(label, 11f, muted)); addView(text(value, 17f, color, true)) }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    fun centered(value: String) = text(value, 14f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(32), dp(12), dp(32)) }
}
