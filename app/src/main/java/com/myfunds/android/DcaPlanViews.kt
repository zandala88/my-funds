package com.myfunds.android

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.myfunds.core.*
import java.math.BigDecimal

/** A plan is a fund, a rule and a budget, with one explicit editing action. */
fun Ui.dcaPlanCard(holding: Holding, groupName: String, budget: DailyDcaBudget, today: String, next: DcaExecution?, pending: Int, toggle: () -> Unit, edit: () -> Unit): View = box().apply {
    val schedule=DcaSchedules.schedule(holding,today)
    setPadding(dp(18), dp(6), dp(18), dp(6))
    addView(bentoCard(18).apply {
        addView(text(holding.name, 15f, bold = true))
        addView(text("${holding.code} · $groupName", 11f, muted))
        val shares = holding.dcaShares?.takeIf { it > BigDecimal.ZERO }
        addView(box(false).apply {
            addView(badge(if (shares != null) "按份额" else "按金额"))
            addView(badge(DcaSchedules.describe(schedule), muted, soft),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(12) })
        addView(box(false).apply {
            addView(box().apply {
                addView(text("每次定投", 11f, muted))
                addView(text(if (shares != null) "${shares.stripTrailingZeros().toPlainString()} 份" else "${amount(holding.dcaAmount)} ${schedule.currency}", 18f, bold = true))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(box().apply {
                addView(text("每次预算", 11f, muted))
                addView(text("${schedule.currency} ${amount(budget.total)}", 18f, accent, true))
                if (budget.missingPrices > 0) addView(text("等待净值", 10f, muted))
                else if (shares != null) addView(text("按最新净值估算", 10f, muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(text(if(schedule.paused) "计划已暂停，暂停期间不补投" else if(next?.executionDate!=null) "下一计划日 ${next.executionDate}" else if(next?.status==DcaStatus.WAITING_CALENDAR) "等待年度交易日历" else "计划已结束",11f,muted))
        addView(text("$pending 期待处理 · ${schedule.holidayPolicy.let { when(it) { HolidayPolicy.NEXT->"休市顺延"; HolidayPolicy.SKIP->"休市跳过"; HolidayPolicy.KEEP->"保留原日" } }}",10f,muted))
        addView(button(if(schedule.paused) "恢复计划" else "暂停计划",action=toggle))
        addView(box(false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(holding.dcaLastDate?.let { "本机检查至\n$it" } ?: "尚未记账", 10f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("编辑计划", action = edit).apply { textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6)) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }, LinearLayout.LayoutParams(-1, -2))
}
