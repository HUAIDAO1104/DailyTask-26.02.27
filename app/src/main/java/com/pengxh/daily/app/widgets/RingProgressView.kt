package com.pengxh.daily.app.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 仪表盘圆形进度环。
 *
 * 深色玻璃风格：底色白 8%，进度色主题蓝，环心绘制「完成/总数」与「今日进度」。
 * 通过 [setProgress] 更新，数值变化自动带动画。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f.dp()
        color = Color.argb(26, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f.dp()
        color = Color.parseColor("#0A84FF")
        strokeCap = Paint.Cap.ROUND
    }

    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f.dp()
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 255, 255, 255)
        textSize = 11f.dp()
        textAlign = Paint.Align.CENTER
    }

    private val ringRect = RectF()
    private var progress = 0f
    private var doneCount = 0
    private var totalCount = 0

    /** @param done 今日已完成数；@param total 今日任务总数（为 0 时进度归零） */
    fun setProgress(done: Int, total: Int) {
        this.doneCount = done.coerceAtLeast(0)
        this.totalCount = total.coerceAtLeast(0)
        val target = if (totalCount == 0) 0f else (doneCount / totalCount.toFloat()).coerceIn(0f, 1f)
        progress = target
        invalidate()
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = trackPaint.strokeWidth
        val inset = stroke / 2f + 1f.dp()
        ringRect.set(inset, inset, width - inset, height - inset)

        canvas.drawArc(ringRect, -90f, 360f, false, trackPaint)
        if (progress > 0.01f) {
            canvas.drawArc(ringRect, -90f, 360f * progress, false, progressPaint)
        }

        val cy = height / 2f
        // 环心计数
        val countBaseline = cy + (countPaint.textSize - countPaint.descent() - countPaint.ascent()) / 2f
        canvas.drawText("$doneCount/$totalCount", width / 2f, countBaseline, countPaint)

        // 环下标签
        val labelBaseline = cy + countPaint.textSize / 2f + labelPaint.textSize + 6f.dp()
        canvas.drawText("今日进度", width / 2f, labelBaseline, labelPaint)
    }
}
