package com.pengxh.daily.app.extensions

import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.extensions.appendZero
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale

fun DailyTaskBean.convertToTimeEntity(): TimeEntity {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val date = dateFormat.parse("${TimeKit.getTodayDate()} ${this.time}")!!
    return TimeEntity.target(date)
}

/**
 * 计算任务执行时间与当前时间之差。
 *
 * 返回 Triple：
 *   first  = 计划时间（原始设定，如 "09:00:00"）
 *   second = 实际执行时间（加偏移后，如 "08:57:32"）
 *   third  = 距实际执行时间的秒数（负数表示已过时，调用方应立即执行）
 *
 * 偏移规则（需开启随机时间开关）：
 *   - 读取 RANDOM_BEFORE_MINUTES_KEY（往前最多N分钟，默认5）
 *   - 读取 RANDOM_AFTER_MINUTES_KEY（往后最多N分钟，默认10）
 *   - 使用 SecureRandom 在 [-beforeSec, +afterSec] 范围内随机一个偏移秒数
 *   - 精确到秒，每次调用都不同（真随机）
 *   - 若偏移后超出当天范围，截断到 [0, 86399]
 *   - 兼容旧版 RANDOM_MINUTE_RANGE_KEY（若新键未设置则 fallback 到旧键）
 */
fun DailyTaskBean.diffCurrent(): Triple<String, String, Int> {
    val needRandom = SaveKeyValues.getValue(Constant.RANDOM_TIME_KEY, true) as Boolean

    // 原始计划时间（秒）
    val array = this.time.split(":")
    val plannedTotalSeconds = array[0].toInt() * 3600 + array[1].toInt() * 60 + array[2].toInt()
    val plannedTime = this.time  // 保存计划时间字符串

    var actualTotalSeconds = plannedTotalSeconds

    if (needRandom) {
        // 读取前/后偏移分钟数（fallback 到旧版 RANDOM_MINUTE_RANGE_KEY）
        val legacyRange = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
        val beforeMinutes = SaveKeyValues.getValue(
            Constant.RANDOM_BEFORE_MINUTES_KEY, legacyRange
        ) as Int
        val afterMinutes = SaveKeyValues.getValue(
            Constant.RANDOM_AFTER_MINUTES_KEY, legacyRange * 2
        ) as Int

        // 偏移范围：[-beforeSec, +afterSec]（秒）
        val beforeSec = beforeMinutes * 60
        val afterSec = afterMinutes * 60
        val totalRange = beforeSec + afterSec  // 总范围

        if (totalRange > 0) {
            // SecureRandom 保证真随机，不同日期/不同次调用结果均不同
            val secureRandom = SecureRandom()
            val offsetSec = secureRandom.nextInt(totalRange + 1) - beforeSec
            actualTotalSeconds = (plannedTotalSeconds + offsetSec).coerceIn(0, 86399)
        }
    }

    // 转换实际时间为 HH:mm:ss 格式
    val h = actualTotalSeconds / 3600
    val m = (actualTotalSeconds % 3600) / 60
    val s = actualTotalSeconds % 60
    val actualTime = "${h.appendZero()}:${m.appendZero()}:${s.appendZero()}"

    // 计算距实际执行时间的秒数（负数 = 已过时，立即执行）
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val taskDate = sdf.parse("${TimeKit.getTodayDate()} $actualTime") ?: return Triple(
        plannedTime, actualTime, 0
    )
    val diffSeconds = ((taskDate.time - System.currentTimeMillis()) / 1000).toInt()

    return Triple(plannedTime, actualTime, diffSeconds)
}
