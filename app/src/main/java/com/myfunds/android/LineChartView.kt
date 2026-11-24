package com.myfunds.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.myfunds.core.ChartPoint

class LineChartView(context: Context, private val points: List<ChartPoint>, private val ui: Ui) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selected = -1
    init { minimumHeight = ui.dp(230); contentDescription = if (points.isEmpty()) "暂无图表数据" else "净值或行情走势，${points.first().date} 至 ${points.last().date}，可按住查看数值" }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = ui.dp(55).toFloat(); val top = ui.dp(34).toFloat(); val right = width - ui.dp(12).toFloat(); val bottom = height - ui.dp(30).toFloat()
        val values = points.map { it.value.toDouble() }; var low = values.min(); var high = values.max()
        if (high == low) { low -= 0.01; high += 0.01 }
        val span = high - low; low -= span * .08; high += span * .08
        fun x(i: Int) = left + (right - left) * i / (points.size - 1).coerceAtLeast(1)
        fun y(i: Int) = (bottom - (values[i] - low) / (high - low) * (bottom - top)).toFloat()
        pen.textSize = ui.dp(10).toFloat(); pen.strokeWidth = ui.dp(1).toFloat()
        for (i in 0..3) {
            val line = top + (bottom - top) * i / 3
            pen.color = ui.muted; pen.alpha = 40; canvas.drawLine(left, line, right, line, pen); pen.alpha = 255
            canvas.drawText(String.format(java.util.Locale.ROOT, "%.3f", high - (high - low) * i / 3), 0f, line + 3, pen)
        }
        val path = Path(); points.indices.forEach { i -> if (i == 0) path.moveTo(x(i), y(i)) else path.lineTo(x(i), y(i)) }
        pen.color = ui.accent; pen.strokeWidth = ui.dp(2).toFloat(); pen.style = Paint.Style.STROKE; canvas.drawPath(path, pen); pen.style = Paint.Style.FILL
        pen.color = ui.muted; canvas.drawText(points.first().date.take(10), left, height - ui.dp(8).toFloat(), pen)
        if (points.size > 1) canvas.drawText(points.last().date.take(10), right - ui.dp(70), height - ui.dp(8).toFloat(), pen)
        if (selected >= 0) {
            val i = selected.coerceIn(points.indices); pen.color = ui.accent; canvas.drawCircle(x(i), y(i), ui.dp(4).toFloat(), pen)
            pen.color = ui.ink; pen.textSize = ui.dp(12).toFloat(); canvas.drawText("${points[i].date}   ${points[i].value.toPlainString()}", left, ui.dp(18).toFloat(), pen)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            parent.requestDisallowInterceptTouchEvent(true)
            val left = ui.dp(55); val usable = (width - left - ui.dp(12)).coerceAtLeast(1)
            selected = ((event.x - left) / usable * (points.size - 1)).toInt().coerceIn(points.indices); invalidate(); return true
        }
        if (event.action == MotionEvent.ACTION_UP) { parent.requestDisallowInterceptTouchEvent(false); performClick(); return true }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
