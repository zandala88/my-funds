package com.myfunds.android

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.myfunds.core.DailyDcaBudget
import com.myfunds.core.Holding
import java.math.BigDecimal

/** A plan is a fund, a rule and a budget, with one explicit editing action. */
fun Ui.dcaPlanCard(holding: Holding, groupName: String, budget: DailyDcaBudget, edit: () -> Unit): View = box().apply {
    setPadding(dp(18), dp(6), dp(18), dp(6))
    addView(bentoCard(18).apply {
        addView(text(holding.name, 15f, bold = true))
        addView(text("${holding.code} · $groupName", 11f, muted))
        val shares = holding.dcaShares?.takeIf { it > BigDecimal.ZERO }
        addView(box(false).apply {
            addView(badge(if (shares != null) "按份额" else "按金额"))
            addView(badge(if (holding.dcaWeekdaysOnly) "周一至周五" else "每天", muted, soft),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(12) })
        addView(box(false).apply {
            addView(box().apply {
                addView(text("每次定投", 11f, muted))
                addView(text(if (shares != null) "${shares.stripTrailingZeros().toPlainString()} 份" else "${amount(holding.dcaAmount)} 元", 18f, bold = true))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(box().apply {
                addView(text("单日预计", 11f, muted))
                addView(text("¥${amount(budget.total)}", 18f, accent, true))
                if (budget.missingPrices > 0) addView(text("等待净值", 10f, muted))
                else if (shares != null) addView(text("按最新净值估算", 10f, muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(box(false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(holding.dcaLastDate?.let { "本机记账至\n$it" } ?: "尚未记账", 10f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("编辑计划", action = edit).apply { textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6)) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }, LinearLayout.LayoutParams(-1, -2))
}
