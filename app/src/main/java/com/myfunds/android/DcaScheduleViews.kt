package com.myfunds.android

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.widget.*
import com.myfunds.core.*
import java.math.BigDecimal
import java.time.LocalDate

internal class DcaScheduleViews(private val ui: Ui) {
    fun show(h: Holding,today: String,currency: String,save: (DcaSchedule,BigDecimal)->Unit,remove: ()->Unit) {
        val old=DcaSchedules.schedule(h,today)
        val box=ui.box().apply { setPadding(ui.dp(22),ui.dp(8),ui.dp(22),ui.dp(8)) }
        val amount=ui.field("每次定投金额 ($currency，如 10.00)",h.dcaAmount?.stripTrailingZeros()?.toPlainString() ?: "",true)
        box.addView(ui.text("仅为本机模拟计划，不会向银行或平台下单。执行日正式净值公布后才折算份额；缺失数据保留等待。",12f,ui.muted)); box.addView(amount)
        fun spinner(label: String,items: List<String>,selected: Int): Spinner {
            box.addView(ui.text(label,12f,ui.muted))
            return Spinner(ui.context).apply { contentDescription=label; adapter=ArrayAdapter(ui.context,android.R.layout.simple_spinner_dropdown_item,items); setSelection(selected); box.addView(this) }
        }
        val frequency=spinner("定投频率",listOf("每个交易日","每天（自然日）","每周","每月"),old.frequency.ordinal)
        val weekdayLabelIndex=box.childCount
        val weekday=spinner("每周执行日",listOf("周一","周二","周三","周四","周五","周六","周日"),old.weekDay-1)
        val monthdayLabelIndex=box.childCount
        val monthday=spinner("每月执行日（不足则取月末）",(1..31).map { "$it 日" },old.monthDay-1)
        val holiday=spinner("休市处理",listOf("顺延至下一交易日","跳过本期","保留原日期，等待当日净值"),old.holidayPolicy.ordinal)
        fun updateFrequency() {
            val weekly=frequency.selectedItemPosition==DcaFrequency.WEEKLY.ordinal
            val monthly=frequency.selectedItemPosition==DcaFrequency.MONTHLY.ordinal
            weekday.visibility=if(weekly) android.view.View.VISIBLE else android.view.View.GONE
            box.getChildAt(weekdayLabelIndex).visibility=weekday.visibility
            monthday.visibility=if(monthly) android.view.View.VISIBLE else android.view.View.GONE
            box.getChildAt(monthdayLabelIndex).visibility=monthday.visibility
        }
        frequency.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?,view: android.view.View?,position: Int,id: Long)=updateFrequency()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        updateFrequency()
        var start=maxOf(today,old.startDate); var end=old.endDate
        fun pick(value: String,onPicked: (String)->Unit) {
            val d=LocalDate.parse(value)
            DatePickerDialog(ui.context,{ _,y,m,day->onPicked(LocalDate.of(y,m+1,day).toString()) },d.year,d.monthValue-1,d.dayOfMonth).apply {
                datePicker.minDate=LocalDate.parse(today).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            }.show()
        }
        lateinit var startButton: Button
        startButton=ui.button("开始日期 $start") { pick(start) { start=it; startButton.text="开始日期 $start" } }; box.addView(startButton)
        lateinit var endButton: Button
        endButton=ui.button("结束日期 ${end ?: "不限"}") { ui.dialog().setTitle("结束日期").setItems(arrayOf("不限","选择日期")) { _,i->
            if(i==0) { end=null; endButton.text="结束日期 不限" } else pick(end ?: start) { end=it; endButton.text="结束日期 $end" }
        }.show() }; box.addView(endButton)
        val paused=Switch(ui.context).apply { text="暂停计划"; setTextColor(ui.ink); isChecked=old.paused }; box.addView(paused)
        box.addView(ui.text("编辑后从所选开始日期启用新规则，旧记录保留；今日已记过定投时，新计划最早明日生效。交易日计划会跳过休市日，未核实年度日历时等待更新。暂停期间不补投。",11f,ui.muted))
        val builder=ui.dialog().setTitle("${h.name} · 定投").setView(ScrollView(ui.context).apply { addView(box) }).setPositiveButton("保存计划",null).setNegativeButton("取消",null)
        if(DcaSchedules.enabled(h)) builder.setNeutralButton("停止计划") { _,_->remove() }
        val dialog=builder.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val n=amount.text.toString().trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("请输入每次定投金额")
                require(n.signum()>0) { "定投金额必须大于零" }; validDecimal(n)
                val s=DcaSchedule(DcaFrequency.entries[frequency.selectedItemPosition],start,weekday.selectedItemPosition+1,monthday.selectedItemPosition+1,end,HolidayPolicy.entries[holiday.selectedItemPosition],paused.isChecked,currency)
                s.validate(); save(s,n); dialog.dismiss()
            } catch(e: Exception) { Toast.makeText(ui.context,e.message ?: "计划无效",Toast.LENGTH_LONG).show() }
        } }; dialog.show()
    }
}
