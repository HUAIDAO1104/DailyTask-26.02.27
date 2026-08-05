package com.pengxh.daily.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.AppConfigManager
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.SkipDates
import com.pengxh.daily.app.utils.WeekSchedule
import com.pengxh.daily.app.widgets.CalendarMultiSelectDialog
import com.pengxh.daily.app.widgets.TaskMessageDialog
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val context = this
    private val hourArray = arrayListOf("0", "1", "2", "3", "4", "5", "6", "自定义（单位：时）")
    private val timeArray = arrayListOf("15", "30", "45", "自定义（单位：秒）")
    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        binding.resetTimeView.text = "每天${hour}点"
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as Int
        binding.timeoutTextView.text = "${time}s"
        binding.keyTextView.text = SaveKeyValues.getValue(Constant.TASK_NAME_KEY, "打卡") as String
        binding.autoTaskSwitch.isChecked = SaveKeyValues.getValue(
            Constant.TASK_AUTO_START_KEY, true
        ) as Boolean
        val needRandom = SaveKeyValues.getValue(Constant.RANDOM_TIME_KEY, true) as Boolean
        binding.randomTimeSwitch.isChecked = needRandom
        if (needRandom) {
            binding.minuteRangeLayout.visibility = View.VISIBLE
            val legacyRange = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
            val beforeVal = SaveKeyValues.getValue(Constant.RANDOM_BEFORE_MINUTES_KEY, legacyRange) as Int
            val afterVal = SaveKeyValues.getValue(Constant.RANDOM_AFTER_MINUTES_KEY, legacyRange * 2) as Int
            binding.beforeMinutesView.text = "${beforeVal}分钟"
            binding.afterMinutesView.text = "${afterVal}分钟"
        } else {
            binding.minuteRangeLayout.visibility = View.GONE
        }

        refreshWhitelistView()
        binding.weekScheduleView.text = WeekSchedule.summary()
        refreshSkipDatesView()
        refreshHolidayFailView()
    }

    private fun refreshWhitelistView() {
        val whitelist = SaveKeyValues.getValue(Constant.COMMAND_WHITELIST_KEY, "") as String
        binding.whitelistView.text = whitelist.ifBlank { "未设置" }
    }

    private fun refreshSkipDatesView() {
        val count = SkipDates.getAll().size
        binding.skipDatesView.text = if (count > 0) "${count}天" else "无"
    }

    private fun refreshHolidayFailView() {
        val preferWork = SaveKeyValues.getValue(Constant.HOLIDAY_FAIL_WORK_KEY, true) as Boolean
        binding.holidayFailView.text = if (preferWork) "按星期兜底" else "保守跳过"
    }

    override fun initEvent() {
        binding.resetTimeLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(hourArray)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setHourByPosition(position)
                    }
                }).build().show()
        }

        binding.timeoutLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(timeArray)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setTimeByPosition(position)
                    }
                }).build().show()
        }

        binding.keyLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置打卡口令")
                .setHintMessage("请输入打卡口令，如：打卡")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        SaveKeyValues.putValue(Constant.TASK_NAME_KEY, value)
                        binding.keyTextView.text = value
                    }

                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.randomTimeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.RANDOM_TIME_KEY, isChecked)
            if (isChecked) {
                binding.minuteRangeLayout.visibility = View.VISIBLE
                val legacyRange = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
                val beforeVal = SaveKeyValues.getValue(Constant.RANDOM_BEFORE_MINUTES_KEY, legacyRange) as Int
                val afterVal = SaveKeyValues.getValue(Constant.RANDOM_AFTER_MINUTES_KEY, legacyRange * 2) as Int
                binding.beforeMinutesView.text = "${beforeVal}分钟"
                binding.afterMinutesView.text = "${afterVal}分钟"
            } else {
                binding.minuteRangeLayout.visibility = View.GONE
            }
        }

        binding.beforeMinutesLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置最多提前分钟数")
                .setHintMessage("请输入 0~120 之间的整数，如：5（表示最早可提前5分钟）")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        val v = value.toIntOrNull()
                        if (v != null && v in 0..120) {
                            binding.beforeMinutesView.text = "${v}分钟"
                            SaveKeyValues.putValue(Constant.RANDOM_BEFORE_MINUTES_KEY, v)
                        } else {
                            "请输入 0~120 之间的整数分钟数".show(context)
                        }
                    }
                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.afterMinutesLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置最多推迟分钟数")
                .setHintMessage("请输入 0~120 之间的整数，如：10（表示最晚可推迟10分钟）")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        val v = value.toIntOrNull()
                        if (v != null && v in 0..120) {
                            binding.afterMinutesView.text = "${v}分钟"
                            SaveKeyValues.putValue(Constant.RANDOM_AFTER_MINUTES_KEY, v)
                        } else {
                            "请输入 0~120 之间的整数分钟数".show(context)
                        }
                    }
                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.whitelistLayout.setOnClickListener {
            val current = SaveKeyValues.getValue(Constant.COMMAND_WHITELIST_KEY, "") as String
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置指令白名单")
                .setHintMessage("昵称/群名，多个用逗号分隔；留空则不限制。当前：${current.ifBlank { "未设置" }}")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        SaveKeyValues.putValue(Constant.COMMAND_WHITELIST_KEY, value.trim())
                        refreshWhitelistView()
                        if (value.isBlank()) {
                            "已清空白名单，将响应所有人发来的指令".show(context)
                        } else {
                            "白名单已更新，只响应名单内来源的指令".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.weekScheduleLayout.setOnClickListener {
            val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            // 该数组会被多选对话框直接修改，确定时即为最终选中状态
            val checked = WeekSchedule.checkedArray()
            AlertDialog.Builder(this)
                .setTitle("选择打卡星期")
                .setMultiChoiceItems(names, checked, null)
                .setPositiveButton("确定") { _, _ ->
                    val schedule = WeekSchedule.fromChecked(checked)
                    if (schedule == null) {
                        "至少选择一天".show(context)
                    } else {
                        WeekSchedule.saveSchedule(schedule)
                        binding.weekScheduleView.text = WeekSchedule.summary()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.skipDatesLayout.setOnClickListener {
            showSkipDatesDialog()
        }

        binding.holidayFailLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(arrayListOf("按星期兜底打卡（推荐）", "保守跳过打卡"))
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        SaveKeyValues.putValue(Constant.HOLIDAY_FAIL_WORK_KEY, position == 0)
                        refreshHolidayFailView()
                    }
                }).build().show()
        }

        binding.outputLayout.setOnClickListener {
            val taskBeans = DatabaseWrapper.loadAllTask()

            if (taskBeans.isEmpty()) {
                "没有任务可以导出".show(this)
                return@setOnClickListener
            }

            TaskMessageDialog.Builder()
                .setContext(this)
                .setTasks(taskBeans)
                .setOnDialogButtonClickListener(object :
                    TaskMessageDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(taskValue: String) {
                        val cipData = ClipData.newPlainText("DailyTask", taskValue)
                        clipboard.setPrimaryClip(cipData)
                        "任务已复制到剪切板".show(context)
                    }
                }).build().show()
        }
    }

    /** 请假日历多选对话框：点击日期添加/取消，支持批量选多天与单独取消某天 */
    private fun showSkipDatesDialog() {
        CalendarMultiSelectDialog.Builder()
            .setContext(this)
            .setInitialDates(SkipDates.getAll())
            .setOnDialogButtonClickListener(object :
                CalendarMultiSelectDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(selectedDates: List<String>) {
                    val old = SkipDates.getAll()
                    val newSet = selectedDates.toSet()
                    val added = newSet - old
                    val removed = old - newSet
                    removed.forEach { SkipDates.remove(it) }
                    added.forEach { SkipDates.add(it) }
                    notifySkipDatesChanged()
                    refreshSkipDatesView()
                    when {
                        added.isEmpty() && removed.isEmpty() ->
                            "请假日期未变化".show(context)

                        added.isNotEmpty() && removed.isNotEmpty() ->
                            "已添加 ${added.size} 天，取消 ${removed.size} 天".show(context)

                        added.isNotEmpty() ->
                            "已添加 ${added.size} 天请假日期".show(context)

                        else ->
                            "已取消 ${removed.size} 天请假日期".show(context)
                    }
                }
            }).build().show()
    }

    /** 通知任务引擎请假日期已变化，由其重新评估今日任务 */
    private fun notifySkipDatesChanged() {
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.SKIP_DATES_CHANGED.action
        )
    }

    private fun setHourByPosition(position: Int) {
        if (position == hourArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置重置时间")
                .setHintMessage("请输入 0~23 之间的整数小时，如：6")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        val hour = value.toIntOrNull()
                        if (hour != null && hour in 0..23) {
                            binding.resetTimeView.text = "每天${hour}点"
                            setTaskResetTime(hour)
                        } else {
                            "请输入 0~23 之间的整数小时".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            val hour = hourArray[position].toInt()
            binding.resetTimeView.text = "每天${hour}点"
            setTaskResetTime(hour)
        }
    }

    private fun setTaskResetTime(hour: Int) {
        SaveKeyValues.putValue(Constant.RESET_TIME_KEY, hour)
        // 重新开始重置每日任务计时
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.SET_RESET_TASK_TIME.action, mapOf("hour" to hour)
        )
    }

    private fun setTimeByPosition(position: Int) {
        if (position == timeArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置超时时间")
                .setHintMessage("请输入 5~600 之间的整数秒数，如：60")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        val time = value.toIntOrNull()
                        if (time != null && time in 5..600) {
                            binding.timeoutTextView.text = "${time}s"
                            updateDingDingTimeout(time)
                        } else {
                            "请输入 5~600 之间的整数秒数".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            val time = timeArray[position].toInt()
            binding.timeoutTextView.text = "${time}s"
            updateDingDingTimeout(time)
        }
    }

    private fun updateDingDingTimeout(time: Int) {
        SaveKeyValues.putValue(Constant.STAY_DD_TIMEOUT_KEY, time)
        // 更新目标应用任务超时时间
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.SET_DING_DING_OVERTIME.action, mapOf("time" to time)
        )
    }

    override fun onPause() {
        super.onPause()
        // 每次离开配置页面时，将最新配置持久化到文件，确保升级后配置不丢失
        AppConfigManager.save(this)
    }
}