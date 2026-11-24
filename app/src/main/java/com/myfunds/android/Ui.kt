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

class Ui(private val baseContext: Context, val dark: Boolean, private val redUp: Boolean = true,
    val displaySize: com.myfunds.core.DisplaySize = com.myfunds.core.DisplaySize.STANDARD) {
    val scale = displaySize.factor
    // Keep the Activity as the base so dialogs retain its window token. Only this
    // wrapper's resources change; the system and other apps keep their font sizes.
    val context = android.view.ContextThemeWrapper(baseContext, if (dark) R.style.AppTheme_Dark else R.style.AppTheme).apply {
        applyOverrideConfiguration(android.content.res.Configuration(baseContext.resources.configuration).apply { fontScale *= scale })
    }
    val dialogContext = context
    val compact get() = context.resources.configuration.screenWidthDp / scale < 400f
    val touchTarget get() = maxOf(dp(48), (44 * context.resources.displayMetrics.density).toInt())
    fun dialog() = RoundedDialogBuilder(this)
    fun forDisplaySize(size: com.myfunds.core.DisplaySize) = Ui(baseContext, dark, redUp, size)
    fun rawDp(v: Int) = kotlin.math.round(v * context.resources.displayMetrics.density).toInt()
    val headerActionSize get() = rawDp(44)
    val background = Color.parseColor(if (dark) "#0F172A" else "#F1F5F9")
    val card = Color.parseColor(if (dark) "#1E293B" else "#FFFFFF")
    val soft = Color.parseColor(if (dark) "#334155" else "#E2E8F0")
    val ink = Color.parseColor(if (dark) "#F8FAFC" else "#0F172A")
    val muted = Color.parseColor(if (dark) "#94A3B8" else "#64748B")
    val accent = Color.parseColor(if (dark) "#818CF8" else "#4F46E5")
    val border = Color.parseColor(if (dark) "#334155" else "#CBD5E1")
    val green = Color.parseColor(if (dark) "#34D399" else "#059669")
    val rose = Color.parseColor(if (dark) "#FB7185" else "#E11D48")
    fun dp(v: Int) = kotlin.math.round(v * context.resources.displayMetrics.density * scale).toInt()
    fun box(vertical: Boolean = true) = LinearLayout(context).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
    fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }
    fun shape(fill: Int, radius: Int, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
    }
    fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
        minHeight = touchTarget; minimumHeight = touchTarget; includeFontPadding = false
        setTextColor(if (primary) Color.WHITE else if (dark) Color.parseColor("#E2E8F0") else accent)
        val fill = if (primary) accent else soft
        background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x221B352A), shape(fill, 23), shape(Color.WHITE, 23)), dp(2), dp(2), dp(2), dp(2))
        stateListAnimator = null; elevation = 0f; setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { action() }
    }
    fun iconButton(resId: Int, contentDesc: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(resId); contentDescription = contentDesc; tooltipText = contentDesc
        scaleType = ImageView.ScaleType.FIT_CENTER
        val iconSize = rawDp(if (displaySize == com.myfunds.core.DisplaySize.LARGE) 22 else 20)
        val inset = (headerActionSize - iconSize) / 2
        setPadding(inset, inset, inset, inset)
        minimumWidth = headerActionSize; minimumHeight = headerActionSize
        imageTintList = ColorStateList.valueOf(muted)
        background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x22FFFFFF else 0x150F172A), null, shape(Color.WHITE, 22))
        stateListAnimator = null; elevation = 0f
        setOnClickListener { action() }
    }
    fun field(hintText: String, value: String = "", numeric: Boolean = false) = EditText(context).apply {
        hint = hintText; setText(value); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(true)
        background = shape(soft, 14, border, 1); setPadding(dp(14), dp(10), dp(14), dp(10)); minHeight = touchTarget
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    fun round(view: android.view.View, color: Int = card, radius: Int = 22, strokeColor: Int = border, strokeWidth: Int = 1) {
        view.background = shape(color, radius, strokeColor, strokeWidth)
    }
    fun panel(radius: Int = 22) = box().apply {
        setPadding(dp(18), dp(16), dp(18), dp(16)); round(this, card, radius, border, 1)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    fun bentoCard(radius: Int = 20, bgColor: Int = card) = box().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        round(this, bgColor, radius, border, 1)
    }
    fun badge(badgeText: String, textColor: Int = accent, bgColor: Int = if (dark) Color.parseColor("#1E1B4B") else Color.parseColor("#EEF2FF")) = TextView(context).apply {
        text = badgeText; textSize = 11f; setTextColor(textColor); setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = shape(bgColor, 8)
    }
    fun amount(n: BigDecimal?, digits: Int = 2): String = n?.setScale(digits, RoundingMode.HALF_UP)?.toPlainString() ?: "—"
    fun signed(n: BigDecimal?, suffix: String = ""): String = n?.let { (if (it > BigDecimal.ZERO) "+" else "") + amount(it) + suffix } ?: "暂无"
    fun changeColor(n: BigDecimal?): Int = when { n == null || n.signum() == 0 -> muted; n.signum() > 0 -> if (redUp) rose else green; else -> if (redUp) green else rose }
    fun stat(parent: LinearLayout, label: String, value: String, color: Int = ink) {
        parent.addView(box().apply { addView(text(label, 11f, muted)); addView(text(value, 16f, color, true)) }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    fun centered(value: String) = text(value, 14f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(32), dp(12), dp(32)) }
}
