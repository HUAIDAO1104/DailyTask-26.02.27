package com.pengxh.daily.app.utils

import android.util.Log
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 节假日检查工具类（多源容灾版）
 *
 * 查询链路（任一成功即返回并缓存当天结果）：
 *   1. timor.tech 免费节假日 API（主源，含调休补班）
 *   2. NateScarlet/holiday-cn（jsdelivr CDN，备用源，含调休补班）
 *   3. 全部失败时按用户配置策略降级：
 *      - 默认：按本地星期判断（周一~周五打卡，周末跳过），比无脑打卡更能避免周末穿帮
 *      - 可选（HOLIDAY_FAIL_WORK_KEY=false）：保守跳过打卡，防止节假日误打卡穿帮
 *
 * 注意：由失败/兜底产生的结果不写当天缓存，让后续任务到点时还有机会重试网络源。
 */
object HolidayChecker {

    private const val kTag = "HolidayChecker"

    /**
     * timor.tech API 返回的日期类型枚举
     */
    private const val TYPE_WORKDAY = 0    // 工作日
    private const val TYPE_WEEKEND = 1    // 周末
    private const val TYPE_HOLIDAY = 2    // 法定节假日
    private const val TYPE_TRANSFER = 3   // 调休补班（需要上班）

    /**
     * 当天查询结果缓存。
     * key = 日期字符串（yyyy-MM-dd），value = 查询结果 Pair
     * 同一天内多次调用直接返回缓存，不重复请求网络。
     * App 进程重启时缓存自动清空，无需手动管理。
     */
    private var cachedDate: String = ""
    private var cachedResult: Pair<Boolean, String>? = null

    /** holiday-cn 年度数据缓存（按年缓存，一年最多请求一次） */
    private var cachedCnYear: String = ""
    private var cachedCnDays: org.json.JSONArray? = null

    /**
     * 检查今天是否需要打卡（同步，需在子线程调用）
     *
     * @return Pair<Boolean, String>
     *         first  = true 表示今天需要打卡，false 表示今天跳过打卡
     *         second = 原因说明（用于写日志和发邮件）
     */
    @Synchronized
    fun shouldWorkToday(): Pair<Boolean, String> {
        val today = getTodayString()

        // 命中当天缓存，直接返回，不重复请求网络
        if (today == cachedDate && cachedResult != null) {
            Log.d(kTag, "命中当天缓存（$today），直接返回：${cachedResult!!.second}")
            return cachedResult!!
        }

        // 源 1：timor.tech
        try {
            val result = parseTimorResult(queryTimorApi(today), today)
            cache(today, result)
            return result
        } catch (e: Exception) {
            Log.w(kTag, "节假日源1(timor.tech)请求失败: ${e.message}")
        }

        // 源 2：holiday-cn（jsdelivr CDN）
        try {
            val result = queryHolidayCn(today)
            cache(today, result)
            return result
        } catch (e: Exception) {
            Log.w(kTag, "节假日源2(holiday-cn)请求失败: ${e.message}")
        }

        // 两个源全部失败：按用户配置的策略降级，且不写缓存（后续到点可重试网络源）
        val preferWork = SaveKeyValues.getValue(Constant.HOLIDAY_FAIL_WORK_KEY, true) as Boolean
        return if (preferWork) {
            Log.w(kTag, "节假日API均失败，按本地星期兜底")
            weekdayVerdict(today, "节假日API均失败，按本地星期兜底")
        } else {
            Log.w(kTag, "节假日API均失败，按配置保守跳过")
            Pair(false, "今天（$today）节假日API均失败，按配置保守处理：跳过打卡任务")
        }
    }

    private fun cache(today: String, result: Pair<Boolean, String>) {
        cachedDate = today
        cachedResult = result
    }

    // ─── 源 1：timor.tech ───────────────────────────────────────────────────

    /**
     * 请求 timor.tech API 获取指定日期的节假日信息
     * 接口：https://timor.tech/api/holiday/info/$date
     */
    private fun queryTimorApi(date: String): JSONObject {
        val apiUrl = "https://timor.tech/api/holiday/info/$date"
        Log.d(kTag, "请求节假日 API: $apiUrl")
        return JSONObject(httpGet(apiUrl))
    }

    /**
     * 解析 timor.tech 返回的 JSON：
     * { "code": 0, "type": { "type": 0, "name": "周一", "week": 1 }, "holiday": null }
     * type: 0=工作日 1=周末 2=节假日 3=调休补班
     */
    private fun parseTimorResult(json: JSONObject, date: String): Pair<Boolean, String> {
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw Exception("timor.tech 返回 code=$code")
        }

        val typeObj = json.optJSONObject("type")
            ?: throw Exception("timor.tech 返回缺少 type 字段")

        val dayType = typeObj.optInt("type", -1)
        val dayName = typeObj.optString("name", "未知")

        return when (dayType) {
            TYPE_WORKDAY -> {
                Pair(true, "今天（$date）是$dayName，正常工作日，执行打卡任务")
            }

            TYPE_TRANSFER -> {
                // 调休补班：虽然可能是周六/日，但需要上班打卡
                val holidayObj = json.optJSONObject("holiday")
                val targetName = holidayObj?.optString("target", "") ?: ""
                val transferNote = if (targetName.isNotBlank()) "（${targetName}前/后调休补班）" else ""
                Pair(true, "今天（$date）是$dayName$transferNote，需要打卡，执行打卡任务")
            }

            TYPE_WEEKEND -> {
                Pair(false, "今天（$date）是$dayName，周末休息，跳过打卡任务")
            }

            TYPE_HOLIDAY -> {
                val holidayObj = json.optJSONObject("holiday")
                val holidayName = holidayObj?.optString("name", dayName) ?: dayName
                Pair(false, "今天（$date）是$holidayName，法定节假日，跳过打卡任务")
            }

            else -> {
                throw Exception("timor.tech 返回未知类型 type=$dayType")
            }
        }
    }

    // ─── 源 2：holiday-cn（jsdelivr CDN）─────────────────────────────────────

    /**
     * 请求 holiday-cn 年度数据并判断指定日期。
     * 接口：https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json
     * 返回：{ "days": [ {"date":"2026-01-01","name":"元旦","isOffDay":true}, ... ] }
     *   - isOffDay=true  → 放假（跳过打卡）
     *   - isOffDay=false → 调休补班（执行打卡）
     *   - 不在列表中     → 普通日，按星期判断
     */
    private fun queryHolidayCn(today: String): Pair<Boolean, String> {
        val year = today.substring(0, 4)
        if (year != cachedCnYear || cachedCnDays == null) {
            val apiUrl = "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json"
            Log.d(kTag, "请求节假日备用源: $apiUrl")
            val json = JSONObject(httpGet(apiUrl))
            cachedCnDays = json.getJSONArray("days")
            cachedCnYear = year
        }

        val days = cachedCnDays!!
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            if (day.optString("date") == today) {
                val name = day.optString("name", "节假日")
                return if (day.optBoolean("isOffDay")) {
                    Pair(false, "今天（$today）是$name，法定节假日，跳过打卡任务")
                } else {
                    Pair(true, "今天（$today）是${name}调休补班，需要打卡，执行打卡任务")
                }
            }
        }
        // 不在节假日列表：普通日，按星期判断
        return weekdayVerdict(today, "非法定节假日")
    }

    // ─── 公共 ───────────────────────────────────────────────────────────────

    /** 按星期几给出兜底判断（周一~周五打卡，周六日跳过） */
    private fun weekdayVerdict(today: String, note: String): Pair<Boolean, String> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val calendar = Calendar.getInstance().apply {
            time = format.parse(today)!!
        }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY, Calendar.SUNDAY ->
                Pair(false, "今天（$today）是周末（$note），跳过打卡任务")

            else ->
                Pair(true, "今天（$today）是工作日（$note），执行打卡任务")
        }
    }

    /** 简单 GET 请求，非 200 抛异常，5 秒连接/读取超时 */
    private fun httpGet(apiUrl: String): String {
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DailyTask-Android-App")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP 响应码异常: $responseCode")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 获取今天的日期字符串，格式：yyyy-MM-dd
     */
    private fun getTodayString(): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        return format.format(Calendar.getInstance().time)
    }
}
