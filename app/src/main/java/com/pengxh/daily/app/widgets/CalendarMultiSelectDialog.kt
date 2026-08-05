package com.pengxh.daily.app.widgets

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import com.pengxh.daily.app.databinding.DialogCalendarMultiSelectBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 请假日历多选对话框。
 *
 * - 点击日期切换选中/取消（早于今天的日期不可点）
 * - 左右按钮切换月份（最早不超过当前月）
 * - 已选日期以主题色高亮，今天带描边圆圈
 * - 确定后回调最终选中的日期列表，取消/清空不生效
 */
class CalendarMultiSelectDialog private constructor(builder: Builder) : Dialog(builder.context) {
    private val initialDates = builder.initialDates
    private val listener = builder.listener

    private val binding: DialogCalendarMultiSelectBinding by binding()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val today: String = dateFormat.format(Calendar.getInstance().time)
    private val selected = initialDates.toMutableSet()

    /** 当前展示月份（月首，调用方会克隆后再传给适配器） */
    private val monthFirst = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }

    private var calendarAdapter: CalendarGridAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.initDialogLayoutParams(0.9f)
        setCanceledOnTouchOutside(false)

        binding.calendarGrid.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val date = calendarAdapter
                ?.getDateAtPosition(position) ?: return@OnItemClickListener
            // 早于今天的日期不可点选
            if (date < today) return@OnItemClickListener
            if (!selected.add(date)) {
                selected.remove(date)
            }
            calendarAdapter?.notifyDataSetChanged()
            updateCount()
        }

        binding.prevMonthButton.setOnClickListener {
            val minMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
            val candidate = (monthFirst.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            if (!candidate.before(minMonth)) {
                monthFirst.set(candidate.get(Calendar.YEAR), candidate.get(Calendar.MONTH), 1)
                refreshMonthView()
            }
        }

        binding.nextMonthButton.setOnClickListener {
            monthFirst.add(Calendar.MONTH, 1)
            refreshMonthView()
        }

        binding.clearButton.setOnClickListener {
            selected.clear()
            calendarAdapter?.notifyDataSetChanged()
            updateCount()
        }

        binding.cancelButton.setOnClickListener { dismiss() }

        binding.confirmButton.setOnClickListener {
            listener.onConfirmClick(selected.sorted())
            dismiss()
        }

        refreshMonthView()
    }

    private fun refreshMonthView() {
        binding.monthLabel.text =
            "${monthFirst.get(Calendar.YEAR)}年${monthFirst.get(Calendar.MONTH) + 1}月"
        calendarAdapter = CalendarGridAdapter(
            context, monthFirst.clone() as Calendar, selected
        )
        binding.calendarGrid.adapter = calendarAdapter
        val minMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        binding.prevMonthButton.visibility =
            if (monthFirst.after(minMonth)) View.VISIBLE else View.INVISIBLE
        updateCount()
    }

    private fun updateCount() {
        binding.selectedCountView.text = if (selected.isEmpty()) {
            "点击日期可添加或取消请假"
        } else {
            "已选 ${selected.size} 天，点击已选日期可取消"
        }
    }

    class Builder {
        lateinit var context: Context
        lateinit var initialDates: Set<String>
        lateinit var listener: OnDialogButtonClickListener

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setInitialDates(dates: Set<String>): Builder {
            this.initialDates = dates
            return this
        }

        fun setOnDialogButtonClickListener(listener: OnDialogButtonClickListener): Builder {
            this.listener = listener
            return this
        }

        fun build(): CalendarMultiSelectDialog {
            return CalendarMultiSelectDialog(this)
        }
    }

    interface OnDialogButtonClickListener {
        fun onConfirmClick(selectedDates: List<String>)
    }
}
