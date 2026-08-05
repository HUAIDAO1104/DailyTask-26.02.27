package com.pengxh.daily.app.utils

/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/29 12:42
 */
object Constant {
    const val RESET_TIME_KEY = "RESET_TIME_KEY"
    const val STAY_DD_TIMEOUT_KEY = "STAY_DD_TIMEOUT_KEY"
    const val GESTURE_DETECTOR_KEY = "GESTURE_DETECTOR_KEY"
    const val BACK_TO_HOME_KEY = "BACK_TO_HOME_KEY"
    const val TASK_NAME_KEY = "TASK_KEY"
    const val RANDOM_TIME_KEY = "RANDOM_TIME_KEY"
    // 旧版兼容键（单向随机），新版已拆分为前/后两个方向
    const val RANDOM_MINUTE_RANGE_KEY = "RANDOM_MINUTE_RANGE_KEY"
    // 新版：往前最多提前几分钟（如 5 表示最早可提前 5 分钟）
    const val RANDOM_BEFORE_MINUTES_KEY = "RANDOM_BEFORE_MINUTES_KEY"
    // 新版：往后最多推迟几分钟（如 10 表示最晚可推迟 10 分钟）
    const val RANDOM_AFTER_MINUTES_KEY = "RANDOM_AFTER_MINUTES_KEY"
    const val TASK_AUTO_START_KEY = "TASK_AUTO_START_KEY"

    // ── 任务引擎（CountDownTimerService）持久化状态 ─────────────────────────
    /** 任务链是否已启动（今日已布防） */
    const val ENGINE_ACTIVE_KEY = "ENGINE_ACTIVE_KEY"

    /** 任务链布防的日期（yyyy-MM-dd），用于跨天失效判断 */
    const val ENGINE_DATE_KEY = "ENGINE_DATE_KEY"

    /** 今日最后一个打卡成功的任务索引（-1 表示还没有任务成功过） */
    const val ENGINE_LAST_DONE_INDEX_KEY = "ENGINE_LAST_DONE_INDEX_KEY"

    /** 最近一次打卡结果摘要，供远程「状态」指令回报 */
    const val ENGINE_LAST_RESULT_KEY = "ENGINE_LAST_RESULT_KEY"

    /** 当前任务的计划时间/实际执行时间，供打卡日志记录 */
    const val LAST_PLANNED_TIME_KEY = "LAST_PLANNED_TIME_KEY"
    const val LAST_ACTUAL_TIME_KEY = "LAST_ACTUAL_TIME_KEY"

    // ── 打卡日期控制 ────────────────────────────────────────────────────────
    /** 打卡星期设置：7 位 0/1 字符串，第 0 位=周一 … 第 6 位=周日，默认全打卡 */
    const val WEEK_SCHEDULE_KEY = "WEEK_SCHEDULE_KEY"

    /** 请假/跳过打卡的日期列表，JSON 数组（元素 yyyy-MM-dd） */
    const val SKIP_DATES_KEY = "SKIP_DATES_KEY"

    /** 节假日查询全部失败时的策略：true=按工作日打卡（默认），false=按休息跳过 */
    const val HOLIDAY_FAIL_WORK_KEY = "HOLIDAY_FAIL_WORK_KEY"

    // ── 远程指令安全 ────────────────────────────────────────────────────────
    /** 指令发送者白名单（逗号/顿号分隔的昵称或群名），空串=不限制 */
    const val COMMAND_WHITELIST_KEY = "COMMAND_WHITELIST_KEY"

    // ── 预警与自愈 ──────────────────────────────────────────────────────────
    /** 低电量预警已发送的日期（每天最多预警一次） */
    const val LOW_BATTERY_ALERT_DATE_KEY = "LOW_BATTERY_ALERT_DATE_KEY"

    /** 上一次成功执行每日重置的日期（yyyy-MM-dd），用于补偿漏掉的重置 */
    const val LAST_RESET_DATE_KEY = "LAST_RESET_DATE_KEY"

    /** 崩溃自愈：上次崩溃重启时间戳，防止崩溃死循环 */
    const val LAST_CRASH_RESTART_TS_KEY = "LAST_CRASH_RESTART_TS_KEY"

    const val DING_DING = "com.alibaba.android.rimet" // 钉钉
    const val WECHAT = "com.tencent.mm" // 微信
    const val WEWORK = "com.tencent.wework" // 企业微信
    const val QQ = "com.tencent.mobileqq" // QQ
    const val TIM = "com.tencent.tim" // TIM
    const val ZFB = "com.eg.android.AlipayGphone" // 支付宝

    const val FOREGROUND_RUNNING_SERVICE_TITLE = "为保证程序正常运行，请勿移除此通知"
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_OVER_TIME = 30

    // 目标APP
    fun getTargetApp(): String {
        return DING_DING
    }
}