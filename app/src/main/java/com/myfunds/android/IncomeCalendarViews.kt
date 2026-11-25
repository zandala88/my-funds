package com.myfunds.android

import android.app.AlertDialog
import android.view.Gravity
import android.widget.*
import com.myfunds.core.*
import java.time.LocalDate
import java.time.YearMonth

internal class IncomeCalendarViews(private val ui: Ui,private val read: ()->Portfolio,
    private val load: (YearMonth,()->Unit)->Unit={_,done->done()}) {
    fun show(initial: YearMonth=YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"))) {
        var month=initial; var currency="CNY"; var group: String?=null; var loading=false
        val content=ui.box().apply { setPadding(ui.dp(14),ui.dp(8),ui.dp(14),ui.dp(10)) }
        val scroller=ScrollView(ui.context).apply { addView(content) }
        val dialog=ui.dialog().setTitle("收益日历").setView(scroller).setNegativeButton("关闭",null).create()
        lateinit var draw: ()->Unit
        fun request() { val requested=month; loading=true; draw(); load(requested) { if(dialog.isShowing && month==requested) { loading=false; draw() } } }
        draw={
            content.removeAllViews()
            val p=read()
            val rows=p.incomes.filter { it.date.startsWith(month.toString()) && (group==null || it.groupId==group) }
            content.addView(ui.box(false).apply {
                addView(ui.button("‹") { month=month.minusMonths(1); draw(); request() },LinearLayout.LayoutParams(0,-2,1f))
                addView(ui.text("${month.year} 年 ${month.monthValue} 月",16f,bold=true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,-2,3f))
                addView(ui.button("›") { month=month.plusMonths(1); draw(); request() },LinearLayout.LayoutParams(0,-2,1f))
            })
            content.addView(ui.box(false).apply {
                addView(ui.button("本月") { month=YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")); draw(); request() },LinearLayout.LayoutParams(0,-2,1f))
                addView(ui.button("刷新") { request() },LinearLayout.LayoutParams(0,-2,1f))
                addView(ui.button(currency) {
                    val choices=(listOf("CNY")+p.incomes.map { it.currency }).distinct().sorted()
                    ui.dialog().setTitle("收益币种（分别统计）").setItems(choices.toTypedArray()) { _,i->currency=choices[i]; draw() }.show()
                },LinearLayout.LayoutParams(0,-2,1f))
                addView(ui.button(if(group==null) "全部分组" else p.groups.firstOrNull { it.id==group }?.name ?: "历史分组") {
                    val choices=listOf(Group("","全部分组"))+p.groups+p.incomes.map { Group(it.groupId,"历史分组 ${it.groupId.take(8)}") }.filter { old->p.groups.none { it.id==old.id } }.distinctBy { it.id }
                    ui.dialog().setTitle("选择分组").setItems(choices.map { it.name }.toTypedArray()) { _,i->group=choices[i].id.takeIf { it.isNotEmpty() }; draw() }.show()
                },LinearLayout.LayoutParams(0,-2,1.3f))
            })
            val summary=IncomeCalendar.summary(rows,month.atDay(1).toString(),month.atEndOfMonth().toString(),currency)
            content.addView(ui.bentoCard(12).apply {
                addView(ui.text("本月 · $currency",11f,ui.muted))
                addView(ui.text("已公布 ${ui.signed(summary.confirmed)}",18f,ui.changeColor(summary.confirmed),true))
                addView(ui.text("估算 ${ui.signed(summary.estimated)} · 部分贡献 ${ui.signed(summary.partial)}",12f,ui.muted))
                addView(ui.text("待补 ${summary.missing} 条 · ${summary.count} 条基金日记录",11f,ui.muted))
                if(summary.count==0) addView(ui.text("尚无记录。只能从可信持仓基准日起补算。",11f,ui.muted))
            })
            if(loading) content.addView(ui.text("正在获取逐日价格，已有记录保留…",11f,ui.accent))
            content.addView(ui.text("金额按币种分开；估算、部分贡献及待补数据不计入已公布合计。",10f,ui.muted))
            val offset=month.atDay(1).dayOfWeek.value-1
            val cells=((offset+month.lengthOfMonth()+6)/7)*7
            content.addView(ui.box(false).apply { listOf("一","二","三","四","五","六","日").forEach { addView(ui.text(it,12f,ui.muted).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,ui.dp(28),1f)) } })
            for(week in 0 until cells/7) {
                content.addView(ui.box(false).apply {
                    for(col in 0..6) {
                        val n=week*7+col-offset+1
                        if(n !in 1..month.lengthOfMonth()) addView(ui.text(""),LinearLayout.LayoutParams(0,ui.dp(70),1f))
                        else {
                            val date=month.atDay(n).toString(); val dayRows=rows.filter { it.date==date && it.currency==currency }
                            val sum=IncomeCalendar.summary(dayRows,date,date,currency)
                            val hasEstimate=dayRows.any { it.kind==IncomeKind.ESTIMATE }; val partial=dayRows.any { it.kind==IncomeKind.PARTIAL }
                            val amount=when { dayRows.isEmpty()->"—"; dayRows.all { it.kind==IncomeKind.UNKNOWN }->"待补"; else->ui.signed(sum.confirmed+sum.estimated+sum.partial) }
                            val label=when { hasEstimate->"估"; partial->"部分"; sum.missing>0->"待补${sum.missing}"; dayRows.isNotEmpty()->"已公布"; else->"" }
                            addView(ui.box().apply {
                                gravity=Gravity.CENTER; setPadding(ui.dp(1),ui.dp(5),ui.dp(1),ui.dp(5))
                                addView(ui.text(n.toString(),12f,bold=date==LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()).apply { gravity=Gravity.CENTER })
                                addView(ui.text(amount,if(ui.compact) 9f else 10f,ui.changeColor(sum.confirmed+sum.estimated+sum.partial),true).apply { gravity=Gravity.CENTER; maxLines=1; setAutoSizeTextTypeUniformWithConfiguration(7,10,1,android.util.TypedValue.COMPLEX_UNIT_SP) })
                                addView(ui.text(label,8f,ui.muted).apply { gravity=Gravity.CENTER })
                                contentDescription="$date $currency $amount $label"; isFocusable=true; setOnClickListener { detail(date,currency,dayRows) }
                                ui.round(this,ui.soft,6)
                            },LinearLayout.LayoutParams(0,ui.dp(74),1f).apply { setMargins(ui.dp(1),ui.dp(2),ui.dp(1),ui.dp(2)) })
                        }
                    }
                })
                val start=month.atDay(1).plusDays((week*7-offset).toLong()).toString(); val end=LocalDate.parse(start).plusDays(6).toString()
                val s=IncomeCalendar.summary(rows,start,end,currency)
                content.addView(ui.text("本周已公布 ${ui.signed(s.confirmed)} · 估算 ${ui.signed(s.estimated)} · 部分 ${ui.signed(s.partial)} · 待补 ${s.missing}",9f,ui.muted))
            }
            content.addView(ui.text("每日收益＝期初份额对应的净值/价格变动－当日费用。买入当天不享受此前涨幅；分红、折算或基准不明时保留待核对。场内记录为价格变动，未计现金分红。",10f,ui.muted))
        }
        dialog.show(); draw(); request()
    }
    private fun detail(date: String,currency: String,rows: List<IncomeEntry>) {
        val box=ui.box().apply { setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(8)) }
        if(rows.isEmpty()) box.addView(ui.text("暂无记录。休市日、持仓基准日前及尚未取得数据的日期不会虚构收益。",13f,ui.muted))
        rows.take(500).forEach { r->box.addView(ui.bentoCard(12).apply {
            addView(ui.text("${r.name} · ${r.code}",14f,bold=true))
            val label=when(r.kind) { IncomeKind.CONFIRMED->"已公布"; IncomeKind.ESTIMATE->"估算"; IncomeKind.PARTIAL->"部分贡献"; IncomeKind.UNKNOWN->"待补 / 待核对" }
            addView(ui.text("$label ${ui.signed(r.amount)} $currency",16f,ui.changeColor(r.amount),true))
            addView(ui.text("期初 ${r.shares?.stripTrailingZeros()?.toPlainString() ?: "未知"} 份\n${r.source} · ${r.asOf}",11f,ui.muted))
            if(r.note.isNotBlank()) addView(ui.text(r.note,11f,ui.muted))
        }) }
        ui.dialog().setTitle("$date · $currency").setView(ScrollView(ui.context).apply { addView(box) }).setPositiveButton("关闭",null).show()
    }
}
