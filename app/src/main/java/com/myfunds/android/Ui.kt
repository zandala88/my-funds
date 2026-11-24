package com.myfunds.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.*
import java.math.BigDecimal
import java.math.RoundingMode

class Ui(val context: Context, val dark: Boolean) {
    val background = Color.parseColor(if (dark) "#111C19" else "#F6F8F4")
    val card = Color.parseColor(if (dark) "#1C2A25" else "#FFFFFF")
    val soft = Color.parseColor(if (dark) "#283A31" else "#EAF0E8")
    val ink = Color.parseColor(if (dark) "#E8F2EA" else "#1B352A")
    val muted = Color.parseColor(if (dark) "#A8B9AD" else "#748477")
    val accent = Color.parseColor(if (dark) "#BADFC4" else "#27694E")
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    fun box(vertical: Boolean = true) = LinearLayout(context).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
    fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
    fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
        minHeight = dp(52); minimumHeight = dp(52); includeFontPadding = false
        setTextColor(if (primary) if (dark) Color.parseColor("#153523") else Color.WHITE else accent)
        val fill = if (primary) accent else soft
        background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x221B352A), shape(fill, 30), shape(Color.WHITE, 30)), dp(3), dp(4), dp(3), dp(4))
        stateListAnimator = null; elevation = 0f; setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { action() }
    }
    fun field(hintText: String, value: String = "", numeric: Boolean = false) = EditText(context).apply {
        hint = hintText; setText(value); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(true)
        background = shape(soft, 15); setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    fun round(view: android.view.View, color: Int = card, radius: Int = 28) {
        view.background = shape(color, radius)
    }
    fun panel() = box().apply {
        setPadding(dp(20), dp(18), dp(20), dp(18)); round(this)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }
    fun amount(n: BigDecimal?, digits: Int = 2): String = n?.setScale(digits, RoundingMode.HALF_UP)?.toPlainString() ?: "—"
    fun signed(n: BigDecimal?, suffix: String = ""): String = n?.let { (if (it > BigDecimal.ZERO) "+" else "") + amount(it) + suffix } ?: "暂无"
    fun changeColor(n: BigDecimal?): Int = when { n == null || n.signum() == 0 -> muted; n.signum() > 0 -> Color.parseColor(if (dark) "#FF9B88" else "#C65C4F"); else -> accent }
    fun stat(parent: LinearLayout, label: String, value: String, color: Int = ink) {
        parent.addView(box().apply { addView(text(label, 12f, muted)); addView(text(value, 18f, color, true)) }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    fun centered(value: String) = text(value, 14f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(32), dp(12), dp(32)) }
}
