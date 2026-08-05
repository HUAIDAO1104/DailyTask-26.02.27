package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.Calendar

/**
 * 打卡星期设置：一周中哪几天需要打卡（如大小周、排班场景）。
 *
 * 存储格式：7 位 0/1 字符串，第 0 位=周一 … 第 6 位=周日，默认 "1111111"（全打卡）。
 */
object WeekSchedule {

    private const val DEFAULT = "1111111"
    private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 读取 7 位设置串，非法数据自动回退为全打卡 */
    fun getSchedule(): String {
        val raw = SaveKeyValues.getValue(Constant.WEEK_SCHEDULE_KEY, DEFAULT) as String
        return if (raw.length == 7 && raw.all { it == '0' || it == '1' }) raw else DEFAULT
    }

    fun saveSchedule(schedule: String) {
        if (schedule.length == 7 && schedule.all { it == '0' || it == '1' }) {
            SaveKeyValues.putValue(Constant.WEEK_SCHEDULE_KEY, schedule)
        }
    }

    /** 某个索引位（0=周一 … 6=周日）是否打卡 */
    fun isEnabled(index: Int): Boolean {
        if (index !in 0..6) return true
        return getSchedule()[index] == '1'
    }

    /** 今天是否需要打卡（仅看星期设置，不含节假日/请假判断） */
    fun isTodayEnabled(): Boolean {
        // Calendar.DAY_OF_WEEK: 1=周日, 2=周一 … 7=周六 → 映射到 0=周一 … 6=周日
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val index = (dow + 5) % 7
        return isEnabled(index)
    }

    /** 今天的星期名，如 "周三"，用于日志与邮件 */
    fun todayName(): String {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return WEEKDAY_NAMES[(dow + 5) % 7]
    }

    /** 7 个复选框的选中状态，供多选对话框使用 */
    fun checkedArray(): BooleanArray {
        val s = getSchedule()
        return BooleanArray(7) { s[it] == '1' }
    }

    /** 把多选对话框的结果转回 7 位串；全不选视为非法，返回 null */
    fun fromChecked(checked: BooleanArray): String? {
        if (checked.size != 7 || checked.all { !it }) return null
        return checked.joinToString("") { if (it) "1" else "0" }
    }

    /** 概要描述，如 "周一至周五" / "每天" / "自定义" */
    fun summary(): String {
        val s = getSchedule()
        if (s == "1111111") return "每天"
        if (s == "1111100") return "周一至周五"
        val names = (0..6).filter { s[it] == '1' }.joinToString("、") { WEEKDAY_NAMES[it] }
        return names.ifBlank { "每天" }
    }
}
