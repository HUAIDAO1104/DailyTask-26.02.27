package com.pengxh.daily.app.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 请假/指定日期不打卡管理。
 *
 * 存储：SharedPreferences，JSON 数组，元素为 "yyyy-MM-dd" 日期串。
 * 命中的日期全天跳过打卡（与周末/法定节假日同等处理），
 * 早于今天的日期在读取时自动清理，避免列表无限增长。
 */
object SkipDates {

    private val gson by lazy { Gson() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    private fun today(): String = dateFormat.format(Date())

    /** 读取请假日期集合（已自动剔除过期与非法日期） */
    fun getAll(): MutableSet<String> {
        val json = SaveKeyValues.getValue(Constant.SKIP_DATES_KEY, "[]") as String
        val parsed = try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)
        } catch (e: Exception) {
            null
        }
        val today = today()
        // 日期串是定长 yyyy-MM-dd，字典序即时间序；剔除脏数据与早于今天的日期
        return (parsed ?: emptyList())
            .filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && it >= today }
            .toMutableSet()
    }

    private fun saveAll(dates: Set<String>) {
        SaveKeyValues.putValue(
            Constant.SKIP_DATES_KEY, gson.toJson(dates.sorted())
        )
    }

    /** 今天是否请假 */
    fun isTodaySkipped(): Boolean = getAll().contains(today())

    /** 添加一个请假日期（已存在则忽略），返回当前完整列表 */
    fun add(date: String): Set<String> {
        val all = getAll()
        all.add(date)
        saveAll(all)
        return all
    }

    /** 移除一个请假日期，返回当前完整列表 */
    fun remove(date: String): Set<String> {
        val all = getAll()
        all.remove(date)
        saveAll(all)
        return all
    }

    fun clear() {
        saveAll(emptySet())
    }
}
