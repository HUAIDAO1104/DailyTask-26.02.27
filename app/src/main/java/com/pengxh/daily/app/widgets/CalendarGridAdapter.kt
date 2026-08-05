package com.pengxh.daily.app.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.pengxh.daily.app.R
import com.pengxh.kt.lite.extensions.convertColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 请假日历网格适配器。
 *
 * 每个单元格对应一个日期（或空白占位），已选日期高亮主题色；
 * 早于今天的日期置灰不可选；今天用描边圆圈标出。
 * 周一为一周第一天，与「打卡星期」设置保持一致。
 */
class CalendarGridAdapter(
    private val context: Context,
    /** 当前展示月份（月首），用于计算每月的格子布局 */
    private val monthFirst: Calendar,
    /** 已选中的日期集合（yyyy-MM-dd），可直接增删 */
    private val selected: MutableSet<String>
) : BaseAdapter() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val today: String = dateFormat.format(Calendar.getInstance().time)
    private val themeColor = R.color.theme_color.convertColor(context)

    /** 每月第一天前的空白占位个数（周一开头） */
    private val leadingBlanks: Int
    private val daysInMonth: Int

    init {
        val dow = monthFirst.get(Calendar.DAY_OF_WEEK)
        // Calendar.DAY_OF_WEEK: 1=周日…7=周六 → 转 0=周一…6=周日
        leadingBlanks = (dow + 5) % 7
        daysInMonth = monthFirst.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    override fun getCount(): Int = leadingBlanks + daysInMonth

    override fun getItem(position: Int): Any? {
        val day = position - leadingBlanks + 1
        return if (day in 1..daysInMonth) day else null
    }

    /** 网格位置对应的日期串（yyyy-MM-dd），空白占位返回 null */
    fun getDateAtPosition(position: Int): String? {
        val day = position - leadingBlanks + 1
        if (day !in 1..daysInMonth) return null
        return dateFor(day)
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val day = position - leadingBlanks + 1
        if (day !in 1..daysInMonth) {
            return TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    parent.resources.getDimensionPixelSize(R.dimen.dp_40)
                )
            }
        }

        val date = dateFor(day)
        val tv = (convertView as? TextView) ?: TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                parent.resources.getDimensionPixelSize(R.dimen.dp_40)
            )
        }

        tv.text = day.toString()
        tv.isClickable = false
        when {
            date < today -> {
                // 过去日期：置灰不可选
                tv.setTextColor(Color.parseColor("#CCCCCC"))
                tv.background = null
            }

            selected.contains(date) -> {
                // 已选：主题色背景 + 白字
                tv.setTextColor(Color.WHITE)
                tv.background = rounded(themeColor)
            }

            date == today -> {
                // 今天：主题色描边圆圈，未选中
                tv.setTextColor(themeColor)
                tv.background = rounded(Color.TRANSPARENT, themeColor, 1f)
            }

            else -> {
                tv.setTextColor(Color.parseColor("#333333"))
                tv.background = null
            }
        }
        return tv
    }

    private fun dateFor(day: Int): String {
        val cal = Calendar.getInstance().apply {
            time = monthFirst.time
            set(Calendar.DAY_OF_MONTH, day)
        }
        return dateFormat.format(cal.time)
    }

    private fun rounded(
        fillColor: Int, strokeColor: Int = 0, strokeWidth: Float = 0f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (strokeWidth > 0) {
                setStroke(dp(strokeWidth), strokeColor)
            }
        }
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
