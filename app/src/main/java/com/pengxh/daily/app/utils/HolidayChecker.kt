package com.pengxh.daily.app.utils

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 节假日检查工具类
 *
 * 使用 timor.tech 免费节假日 API 判断当天是否为工作日：
 *   - type=0 → 工作日（正常打卡）
 *   - type=1 → 周末（跳过打卡）
 *   - type=2 → 法定节假日（跳过打卡）
 *   - type=3 → 调休补班（正常打卡，即便是周六/周日）
 *
 * 如果 API 请求失败（网络异常、超时等），默认按"工作日"处理，不影响正常打卡。
 */
object HolidayChecker {

    private const val kTag = "HolidayChecker"

    /**
     * API 返回的日期类型枚举
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

    /**
     * 检查今天是否需要打卡（同步，需在子线程调用）
     *
     * @return Pair<Boolean, String>
     *         first  = true 表示今天需要打卡，false 表示今天跳过打卡
     *         second = 原因说明（用于写日志和发邮件）
     */
    fun shouldWorkToday(): Pair<Boolean, String> {
        val today = getTodayString()

        // 命中当天缓存，直接返回，不重复请求网络
        if (today == cachedDate && cachedResult != null) {
            Log.d(kTag, "命中当天缓存（$today），直接返回：${cachedResult!!.second}")
            return cachedResult!!
        }

        return try {
            val result = queryHolidayApi(today)
            val parsed = parseResult(result, today)
            // 写入缓存
            cachedDate = today
            cachedResult = parsed
            parsed
        } catch (e: Exception) {
            Log.w(kTag, "节假日 API 请求失败，降级为正常工作日处理: ${e.message}")
            Pair(true, "节假日 API 请求失败（${e.javaClass.simpleName}），默认按工作日处理，正常打卡")
            // 注意：失败时不写缓存，下次到点时可以重试
        }
    }

    /**
     * 请求 timor.tech API 获取指定日期的节假日信息
     * 接口：https://timor.tech/api/holiday/info/$date
     */
    private fun queryHolidayApi(date: String): JSONObject {
        val apiUrl = "https://timor.tech/api/holiday/info/$date"
        Log.d(kTag, "请求节假日 API: $apiUrl")

        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 8000   // 连接超时 8 秒
            readTimeout = 8000      // 读取超时 8 秒
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DailyTask-Android-App")
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP 响应码异常: $responseCode")
        }

        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        Log.d(kTag, "节假日 API 响应: $responseBody")
        return JSONObject(responseBody)
    }

    /**
     * 解析 API 返回的 JSON，判断是否需要打卡
     *
     * API 返回格式（核心字段）：
     * {
     *   "code": 0,
     *   "type": {
     *     "type": 0,        // 0=工作日 1=周末 2=节假日 3=调休补班
     *     "name": "周一",
     *     "week": 1
     *   },
     *   "holiday": null     // 非节假日时为 null，节假日时有详情
     * }
     */
    private fun parseResult(json: JSONObject, date: String): Pair<Boolean, String> {
        val code = json.optInt("code", -1)
        if (code != 0) {
            Log.w(kTag, "API 返回 code=$code，降级为正常工作日处理")
            return Pair(true, "节假日 API 返回异常（code=$code），默认按工作日处理，正常打卡")
        }

        val typeObj = json.optJSONObject("type")
            ?: run {
                Log.w(kTag, "API 返回数据缺少 type 字段，降级为正常工作日处理")
                return Pair(true, "节假日 API 数据格式异常，默认按工作日处理，正常打卡")
            }

        val dayType = typeObj.optInt("type", -1)
        val dayName = typeObj.optString("name", "未知")

        return when (dayType) {
            TYPE_WORKDAY -> {
                Log.d(kTag, "今天（$date）是工作日（$dayName），正常打卡")
                Pair(true, "今天（$date）是$dayName，正常工作日，执行打卡任务")
            }

            TYPE_TRANSFER -> {
                // 调休补班：虽然可能是周六/日，但需要上班打卡
                val holidayObj = json.optJSONObject("holiday")
                val targetName = holidayObj?.optString("target", "") ?: ""
                val transferNote = if (targetName.isNotBlank()) "（${targetName}前/后调休补班）" else ""
                Log.d(kTag, "今天（$date）是调休补班$transferNote，需要打卡")
                Pair(true, "今天（$date）是$dayName$transferNote，需要打卡，执行打卡任务")
            }

            TYPE_WEEKEND -> {
                Log.d(kTag, "今天（$date）是周末（$dayName），跳过打卡")
                Pair(false, "今天（$date）是$dayName，周末休息，跳过打卡任务")
            }

            TYPE_HOLIDAY -> {
                val holidayObj = json.optJSONObject("holiday")
                val holidayName = holidayObj?.optString("name", dayName) ?: dayName
                Log.d(kTag, "今天（$date）是法定节假日（$holidayName），跳过打卡")
                Pair(false, "今天（$date）是$holidayName，法定节假日，跳过打卡任务")
            }

            else -> {
                Log.w(kTag, "未知的日期类型 type=$dayType，降级为正常工作日处理")
                Pair(true, "节假日 API 返回未知类型（type=$dayType），默认按工作日处理，正常打卡")
            }
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
