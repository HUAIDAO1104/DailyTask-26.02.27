package com.pengxh.daily.app.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 请假/销假指令中的日期文本解析器（本机输入框与远程指令共用）。
 *
 * 支持的自然格式（均为"未来语义"，过去的日期自动向后滚动）：
 *   - 留空          → 今天
 *   - 今天/明天/后天/大后天
 *   - 周三/星期五    → 本周对应日（已过则取下周）
 *   - 下周三/下下周日
 *   - 2026-08-12、2026/8/12、2026年8月12日
 *   - 8月12日、8-12、8/12、8.12
 *   - 12日/12号      → 本月对应日（已过则取下月）
 *   - 区间：A到B、A至B、A~B（含首尾，最多 40 天）
 *   - 列表：A、B、C（顿号/逗号/空格分隔）
 *
 * 解析结果三种：
 *   - Dates      成功得到日期列表
 *   - Invalid    看起来是想设日期但无法解析（调用方应回复帮助提示）
 *   - NotCommand 不像日期指令（普通聊天），调用方应静默忽略
 */
object LeaveDateParser {

    sealed interface Result {
        data class Dates(val dates: List<String>) : Result
        object Invalid : Result
        object NotCommand : Result
    }

    /** 单次区间最多展开的天数，防止误操作生成超长列表 */
    private const val MAX_RANGE_DAYS = 40

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    private fun today(): Calendar = Calendar.getInstance()

    private fun fmt(cal: Calendar): String = dateFormat.format(cal.time)

    private fun todayString(): String = fmt(today())

    /**
     * 解析日期文本。输入应已去掉「请假/销假」前缀并 trim 过。
     */
    fun parse(raw: String): Result {
        val text = raw.trim()
        // 空文本 = 今天（远程「请假」不带参数的场景）
        if (text.isEmpty()) {
            return Result.Dates(listOf(todayString()))
        }

        // 快速判定：既不含数字也不含任何日期关键字 → 普通聊天，静默忽略
        // （防误触发的关键："请假一天回老家"这类聊天不会改任何设置）
        val hasDigit = text.any { it.isDigit() }
        val hasKeyword = listOf("今", "明", "后", "周", "星期").any { text.contains(it) }
        if (!hasDigit && !hasKeyword) {
            return Result.NotCommand
        }

        // 1. 区间：A到B / A至B / A~B（ASCII "-" 不作为分隔符，避免拆散 2026-08-12）。
        // 文本含列表分隔符（顿号/逗号/和/与）时优先按列表解析，否则
        // 「8月12日、8月14日到8月16日」会被误判成从 8月12日 到 8月16日 的区间。
        val hasListSeparator = listOf("、", ",", "，", "和", "与").any { text.contains(it) }
        if (!hasListSeparator) {
            val rangeParts = text.split(Regex("\\s*(?:到|至|~|－|—|–)\\s*"))
                .filter { it.isNotBlank() }
            when {
                rangeParts.size == 2 -> {
                    val start = parseSingle(rangeParts[0]) ?: return Result.Invalid
                    val end = parseSingle(rangeParts[1]) ?: return Result.Invalid
                    val dates = expandRange(start, end) ?: return Result.Invalid
                    // 与列表分支一致：过滤已过去日期（完整年月日不滚动，可能是过去日期）
                    val effective = dates.filter { it >= todayString() }
                    return if (effective.isEmpty()) Result.Invalid else Result.Dates(effective)
                }

                rangeParts.size > 2 -> return Result.Invalid
            }
        }

        // 2. 单个或列表：顿号/逗号/空格/和/与 分隔
        val listParts = text.split(Regex("[、,，\\s]+|和|与"))
            .filter { it.isNotBlank() }
        if (listParts.isEmpty()) {
            return Result.Invalid
        }
        val dates = mutableListOf<String>()
        for (part in listParts) {
            val date = parseSingle(part) ?: return Result.Invalid
            dates.add(date)
        }

        // 过滤已过去的日期（完整年月日格式允许输入过去日期，直接剔除）
        val today = todayString()
        val effective = dates.distinct().sorted().filter { it >= today }
        return if (effective.isEmpty()) Result.Invalid else Result.Dates(effective)
    }

    /**
     * 解析单个日期表达式，成功返回 yyyy-MM-dd，失败返回 null
     */
    private fun parseSingle(token: String): String? {
        val t = token.trim()
        if (t.isEmpty()) return null

        // 相对日
        when (t) {
            "今天" -> return fmt(today())
            "明天" -> return offsetDate(1)
            "后天" -> return offsetDate(2)
            "大后天" -> return offsetDate(3)
        }

        // 星期：周三/星期五/下周三/下下周日
        parseWeekday(t)?.let { return it }

        // 完整日期：2026-08-12、2026/8/12、2026年8月12日、2026.8.12
        Regex("""(\d{4})\s*[-/年.\s]\s*(\d{1,2})\s*[-/月.\s]\s*(\d{1,2})\s*日?""").find(t)
            ?.let { m ->
                return buildDate(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                    rollUnit = 0 // 完整日期不滚动
                )
            }

        // 短日期：8月12日、8-12、8/12、8.12（缺少年份，过期滚到明年）
        Regex("""(\d{1,2})\s*[月\-/.\s]\s*(\d{1,2})\s*日?""").find(t)?.let { m ->
            return buildDate(
                today().get(Calendar.YEAR),
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                rollUnit = Calendar.YEAR
            )
        }

        // 仅日：12日/12号（本月，过期滚到下月）
        Regex("""^(\d{1,2})\s*[日号]$""").find(t)?.let { m ->
            val now = today()
            return buildDate(
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1,
                m.groupValues[1].toInt(),
                rollUnit = Calendar.MONTH
            )
        }

        return null
    }

    /**
     * 解析星期表达式：
     *   周三/星期X    → 本周对应日，已过则取下周同一天
     *   下周三        → 下周（下一个周一所在周）的周三
     *   下下周三      → 再往后一周
     */
    private fun parseWeekday(token: String): String? {
        val m = Regex("""^(下{0,2})\s*(?:周|星期)\s*([一二三四五六日天1-7])$""")
            .find(token) ?: return null
        val downCount = m.groupValues[1].length
        val target = when (m.groupValues[2]) {
            "一", "1" -> 1
            "二", "2" -> 2
            "三", "3" -> 3
            "四", "4" -> 4
            "五", "5" -> 5
            "六", "6" -> 6
            "日", "天", "7" -> 7
            else -> return null
        }

        val cal = today()
        // Calendar.DAY_OF_WEEK: 1=周日…7=周六 → 转为 1=周一…7=周日
        val currentDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

        val delta = if (downCount == 0) {
            // 本周X：若已过去（含今天）则取下周
            var d = target - currentDow
            if (d <= 0) d += 7
            d
        } else {
            // 下周X：先跳到下个周一，再加 (target-1) 天；下下周再加 7 天
            val daysToNextMonday = if (currentDow == 1) 7 else 8 - currentDow
            daysToNextMonday + (target - 1) + 7 * (downCount - 1)
        }
        return offsetDate(delta)
    }

    /**
     * 构建日期并校验合法性。
     * @param rollUnit 过去日期的滚动单位：0=不滚动，Calendar.YEAR / Calendar.MONTH
     */
    private fun buildDate(year: Int, month: Int, day: Int, rollUnit: Int): String? {
        if (month !in 1..12 || day !in 1..31) return null
        val cal = Calendar.getInstance().apply {
            isLenient = false
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return try {
            cal.time // 触发非法日期（如 2 月 30 日）抛异常
            var result = fmt(cal)
            if (rollUnit != 0 && result < todayString()) {
                cal.add(rollUnit, 1)
                result = fmt(cal)
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** 展开闭区间 [start, end]（yyyy-MM-dd 字典序即时间序），超长或倒置返回 null */
    private fun expandRange(start: String, end: String): List<String>? {
        if (start == end) return listOf(start)
        if (end < start) return null
        return try {
            val dates = mutableListOf<String>()
            val cal = Calendar.getInstance().apply { time = dateFormat.parse(start)!! }
            val endCal = Calendar.getInstance().apply { time = dateFormat.parse(end)!! }
            while (!cal.after(endCal)) {
                dates.add(fmt(cal))
                if (dates.size > MAX_RANGE_DAYS) return null
                cal.add(Calendar.DATE, 1)
            }
            dates
        } catch (e: Exception) {
            null
        }
    }

    private fun offsetDate(days: Int): String {
        return fmt(today().apply { add(Calendar.DATE, days) })
    }
}
