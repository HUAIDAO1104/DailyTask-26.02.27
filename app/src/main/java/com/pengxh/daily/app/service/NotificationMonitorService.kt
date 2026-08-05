package com.pengxh.daily.app.service

import android.app.Notification
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.extensions.backToMainActivityOnly
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.AppConfigManager
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LeaveDateParser
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.SkipDates
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.daily.app.utils.WeekSchedule
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {

    private val kTag = "MonitorService"
    private val emailManager by lazy { EmailManager(this) }
    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }
    private val auxiliaryApp = arrayOf(
        Constant.WECHAT, Constant.WEWORK, Constant.QQ, Constant.TIM, Constant.ZFB
    )

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.NOTICE_LISTENER_CONNECTED.action
        )
    }

    /**
     * 当有新通知到来时会回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        // 获取接收消息APP的包名
        val pkg = sbn.packageName
        // 获取接收消息的标题（即时通讯消息通知的标题 = 发送者昵称/群名）
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        // 获取接收消息的内容
        val notice = extras.getString(Notification.EXTRA_TEXT)
        if (notice.isNullOrBlank()) {
            return
        }

        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        if (pkg == targetApp || pkg in auxiliaryApp) {
            NotificationBean().apply {
                packageName = pkg
                notificationTitle = title
                notificationMsg = notice
                postTime = System.currentTimeMillis().timestampToCompleteDate()
            }.also {
                DatabaseWrapper.insertNotice(it)
            }
        }

        // 目标应用打卡通知：匹配完整「打卡成功」，
        // 避免「未成功」「打卡失败」等包含"成功"二字的文案被误判为打卡成功
        if (pkg == targetApp && notice.contains("打卡成功")) {
            // 写入打卡专用日志，带计划时间和实际执行时间（由任务引擎排程时写入）
            val planned = (SaveKeyValues.getValue(Constant.LAST_PLANNED_TIME_KEY, "") as String).ifBlank { null }
            val actual = (SaveKeyValues.getValue(Constant.LAST_ACTUAL_TIME_KEY, "") as String).ifBlank { null }
            LogFileManager.writeCheckinLog(
                "打卡成功",
                "收到打卡成功通知：$notice",
                plannedTime = planned,
                actualTime = actual
            )
            // 通知任务引擎推进任务链（引擎无打卡尝试时会自行忽略）
            BroadcastManager.getDefault().sendBroadcast(
                this, MessageType.CHECKIN_SUCCESS.action
            )
            // 回到主界面并自动恢复假息屏（autoMask=true）
            backToMainActivityOnly(autoMask = true)
            // 邮箱未配置时邮件发不出去，不提示「即将发送」以免误导
            if (DatabaseWrapper.loadEmailConfig() != null) {
                "即将发送通知邮件，请注意查收".show(this)
            }
            emailManager.sendEmail(null, notice, false)
        }

        // 其他消息指令：整条文本精确匹配，防止日常聊天中恰好包含
        // 「启动」「停止」「电量」等词语时被误触发（如"车停在楼下了"）
        if (pkg in auxiliaryApp) {
            // 指令发送者白名单校验：设置白名单后，只响应指定昵称/群名发来的指令
            if (!isCommandSenderAllowed(title)) {
                LogFileManager.writeLog("指令来源「$title」不在白名单中，已忽略：$notice")
                return
            }

            val command = extractCommand(notice)

            // 请假/销假采用前缀匹配（参数是灵活的日期文本，无法穷举）：
            // 如「请假 明天」「请假 8月12日到8月14日」「销假 下周三」。
            // 前置拦截可避免被下方精确匹配的逻辑漏掉，
            // 解析不出日期时按普通聊天静默忽略，防止聊天误触发。
            if (command.startsWith("请假") || command.startsWith("销假")) {
                handleLeave(command)
                return
            }

            when (command) {
                "电量" -> {
                    val capacity = batteryManager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CAPACITY
                    )
                    emailManager.sendEmail(
                        "查询手机电量通知", "当前手机剩余电量为：${capacity}%", false
                    )
                }

                "启动" -> {
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.START_DAILY_TASK.action
                    )
                }

                "停止" -> {
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.STOP_DAILY_TASK.action
                    )
                }

                "开始循环" -> {
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, true)
                    // 同步持久化到配置文件，防止 App 重启后
                    // AppConfigManager 从文件恢复旧值，覆盖远程设置
                    AppConfigManager.save(this)
                    emailManager.sendEmail(
                        "循环任务状态通知", "循环任务状态已更新为：开启", false
                    )
                }

                "暂停循环" -> {
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, false)
                    AppConfigManager.save(this)
                    emailManager.sendEmail(
                        "循环任务状态通知", "循环任务状态已更新为：暂停", false
                    )
                }

                "息屏" -> {
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.SHOW_MASK_VIEW.action
                    )
                }

                "亮屏" -> {
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.HIDE_MASK_VIEW.action
                    )
                }

                "考勤记录" -> {
                    var record = ""
                    var index = 1
                    DatabaseWrapper.loadCurrentDayNotice().forEach {
                        if (it.notificationMsg.contains("考勤打卡")) {
                            record += "【第${index}次】${it.notificationMsg}，时间：${it.postTime}\r\n"
                            index++
                        }
                    }
                    emailManager.sendEmail("当天考勤记录通知", record, false)
                }

                "状态" -> {
                    emailManager.sendEmail("任务状态回报", buildStatusReport(), false)
                }

                else -> {
                    val key = SaveKeyValues.getValue(Constant.TASK_NAME_KEY, "打卡") as String
                    // 口令为空串时不能匹配任何消息，否则所有消息都会唤起目标应用
                    if (key.isNotBlank() && command == key) {
                        // 转发给任务引擎统一处理（手动打卡）
                        BroadcastManager.getDefault().sendBroadcast(
                            this, MessageType.MANUAL_CHECKIN.action
                        )
                    }
                }
            }
        }
    }

    /**
     * 提取消息文本中的指令内容，用于精确匹配：
     * 1. 群聊消息的通知格式通常为「昵称: 消息」或「昵称：消息」，取最后一个冒号之后的内容；
     * 2. 去掉首尾空白字符，避免「启动 」之类的消息匹配失败。
     */
    private fun extractCommand(notice: String): String {
        return notice.substringAfterLast('：')
            .substringAfterLast(": ")
            .trim()
    }

    /**
     * 处理「请假/销假」指令（前缀匹配，支持灵活日期参数）：
     *   - 请假 [日期]  将解析出的日期全部加入请假列表（留空 = 今天）
     *   - 销假 [日期]  将解析出的日期移出请假列表；「销假 全部」清空整个列表
     * 解析结果：
     *   - Dates      成功，写列表并发邮件回执
     *   - Invalid    看起来想设日期但格式不对，回邮件附帮助示例
     *   - NotCommand 不像日期指令（普通聊天），静默忽略
     */
    private fun handleLeave(command: String) {
        val isLeave = command.startsWith("请假")
        val arg = command.removePrefix(if (isLeave) "请假" else "销假").trim()

        // 「销假 全部 / 销假 所有」：清空整个请假列表
        if (!isLeave && (arg == "全部" || arg == "所有")) {
            SkipDates.clear()
            sendLeaveConfirm(emptyList(), leave = false, clearedAll = true)
            BroadcastManager.getDefault().sendBroadcast(
                this, MessageType.SKIP_DATES_CHANGED.action
            )
            return
        }

        when (val result = LeaveDateParser.parse(arg)) {
            is LeaveDateParser.Result.NotCommand -> {
                // 不含数字/日期关键字，判定为普通聊天，静默忽略
                LogFileManager.writeLog("疑似普通聊天，已忽略：$command")
            }

            is LeaveDateParser.Result.Invalid -> {
                emailManager.sendEmail(
                    "请假设置失败",
                    "无法解析日期：${arg.ifBlank { "(空)" }}\n\n" +
                        "支持格式示例：\n" +
                        "请假 明天\n" +
                        "请假 8月12日\n" +
                        "请假 8月12日到8月14日\n" +
                        "请假 下周三到下周五\n" +
                        "销假 8月12日\n" +
                        "销假 全部",
                    false
                )
            }

            is LeaveDateParser.Result.Dates -> {
                if (isLeave) {
                    result.dates.forEach { SkipDates.add(it) }
                } else {
                    result.dates.forEach { SkipDates.remove(it) }
                }
                sendLeaveConfirm(result.dates, leave = isLeave)
                BroadcastManager.getDefault().sendBroadcast(
                    this, MessageType.SKIP_DATES_CHANGED.action
                )
            }
        }
    }

    /** 请假/销假操作后的邮件回执，附带当前剩余请假列表 */
    private fun sendLeaveConfirm(dates: List<String>, leave: Boolean, clearedAll: Boolean = false) {
        val current = SkipDates.getAll().sorted()
        val body = buildString {
            when {
                clearedAll -> append("已销假全部日期，请假列表已清空。")
                leave -> append("已将以下 ${dates.size} 天加入请假列表：\n${dates.joinToString("、")}")
                else -> append("已将以下 ${dates.size} 天移出请假列表：\n${dates.joinToString("、")}")
            }
            if (current.isNotEmpty()) {
                append("\n\n当前剩余请假日期：\n${current.joinToString("、")}")
            } else {
                append("\n\n当前请假列表为空，后续打卡任务不受影响。")
            }
        }
        emailManager.sendEmail(
            if (leave) "请假设置通知" else "销假设置通知", body, false
        )
    }

    /**
     * 指令发送者白名单校验。
     * 白名单为空 = 不限制（保持旧行为）；
     * 非空时，只有通知标题（发送者昵称/群名）在名单中才放行指令。
     * 多个名字用英文逗号、中文逗号或顿号分隔。
     */
    private fun isCommandSenderAllowed(title: String): Boolean {
        val whitelist = SaveKeyValues.getValue(Constant.COMMAND_WHITELIST_KEY, "") as String
        if (whitelist.isBlank()) {
            return true
        }
        val senders = whitelist.split(",", "，", "、")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (senders.isEmpty()) {
            return true
        }
        return senders.any { it == title }
    }

    /** 「状态」指令的回报内容 */
    private fun buildStatusReport(): String {
        val sb = StringBuilder()
        val today = TimeKit.getTodayDate()
        sb.append("日期：$today ${WeekSchedule.todayName()}\n")

        val active = SaveKeyValues.getValue(Constant.ENGINE_ACTIVE_KEY, false) as Boolean
        val date = SaveKeyValues.getValue(Constant.ENGINE_DATE_KEY, "") as String
        val running = active && date == today
        sb.append("任务链：${if (running) "运行中" else "未运行"}\n")

        if (SkipDates.isTodaySkipped()) {
            sb.append("今日安排：已请假，全天跳过打卡\n")
        } else if (!WeekSchedule.isTodayEnabled()) {
            sb.append("今日安排：不在打卡星期设置内，全天跳过打卡\n")
        }

        val tasks = DatabaseWrapper.loadAllTask()
        val lastDone = SaveKeyValues.getValue(Constant.ENGINE_LAST_DONE_INDEX_KEY, -1) as Int
        sb.append("今日任务：已完成 ${lastDone + 1}/${tasks.size} 个\n")

        if (running && lastDone + 1 < tasks.size) {
            val planned = SaveKeyValues.getValue(Constant.LAST_PLANNED_TIME_KEY, "") as String
            val actual = SaveKeyValues.getValue(Constant.LAST_ACTUAL_TIME_KEY, "") as String
            sb.append("下一任务：第${lastDone + 2}个，计划 $planned，预计 $actual 执行\n")
        }

        val lastResult = SaveKeyValues.getValue(Constant.ENGINE_LAST_RESULT_KEY, "") as String
        if (lastResult.isNotBlank()) {
            sb.append("最近结果：$lastResult\n")
        }

        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        sb.append("手机电量：${capacity}%\n")
        sb.append("应用版本：${BuildConfig.VERSION_NAME}")
        return sb.toString()
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.NOTICE_LISTENER_DISCONNECTED.action
        )
    }
}
