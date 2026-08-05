package com.pengxh.daily.app.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.backToMainActivityOnly
import com.pengxh.daily.app.extensions.diffCurrent
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HolidayChecker
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.SkipDates
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.daily.app.utils.WeekSchedule
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 任务引擎服务（前台服务，常驻）。
 *
 * 职责：每日任务链的调度与执行，独立于 MainActivity 存活：
 *   - 排程：到点触发用 AlarmManager.setAlarmClock（Doze/灭屏不延迟），
 *     CountDownTimer 仅用于通知文本的秒级刷新（展示用途，延迟无影响）；
 *   - 执行：到点后节假日/请假/星期检查 → 网络预热 → 拉起目标应用；
 *   - 结果：打卡超时重试（最多 3 次，间隔 30s）、成功推进、失败终止（保持旧版语义）；
 *   - 恢复：进度（已完成索引/布防日期）持久化到 SharedPreferences，
 *     进程或服务重启后自动恢复当日任务链。
 *
 * MainActivity 只是 UI：通过 TASK_STATE_CHANGED 广播观察状态，
 * 通过 START/STOP_DAILY_TASK 广播下发指令。
 */
class CountDownTimerService : Service() {

    private val kTag = "CountDownTimerService"
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, "countdown_timer_service_channel").apply {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentText("倒计时服务已就绪")
            setPriority(NotificationCompat.PRIORITY_MIN)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setShowWhen(true)
            setSound(null)
            setVibrate(null)
        }
    }
    private val notificationId = 1001
    private val emailManager by lazy { EmailManager(this) }
    private val alarmManager by lazy { getSystemService(ALARM_SERVICE) as AlarmManager }

    // ── 引擎状态 ────────────────────────────────────────────────────────────
    enum class EngineState { IDLE, COUNTING, WAITING_CHECKIN, RETRY_WAIT, DONE, STOPPED, SKIPPED, FAILED }

    private var state = EngineState.IDLE

    /** 当前正在倒计时/等待结果的任务索引（-1 = 无任务上下文，如手动打卡） */
    private var currentTaskIndex = -1

    /** 手动打卡前的引擎状态，手动打卡结束后恢复（手动打卡不干扰任务链状态） */
    private var stateBeforeManual = EngineState.IDLE

    // ── 计时器（全部仅在本服务内使用）──────────────────────────────────────
    /** 倒计时秒级展示（触发不靠它，靠 AlarmManager） */
    private var displayTimer: CountDownTimer? = null

    /** 打卡超时计时 */
    private var timeoutTimer: CountDownTimer? = null

    /** 重试等待计时 */
    private var retryWaitTimer: CountDownTimer? = null
    private var retryCount = 0

    /** 休息/请假邮件当天只发一次（进程内标志，与旧版语义一致） */
    private var isSkipEmailSent = false

    companion object {
        /** 任务闹钟触发动作（TaskAlarmReceiver → 本服务） */
        const val ACTION_TASK_FIRED = "com.pengxh.daily.app.ACTION_TASK_FIRED"

        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_INTERVAL_SECONDS = 30L
    }

    // ── 广播接收：任务链控制指令 ────────────────────────────────────────────
    private val engineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (MessageType.fromAction(intent?.action ?: return)) {
                MessageType.RESET_DAILY_TASK -> resetDaily()
                MessageType.START_DAILY_TASK -> startChain()
                MessageType.STOP_DAILY_TASK -> stopChain()
                MessageType.CHECKIN_SUCCESS -> onCheckinSuccess()
                MessageType.MANUAL_CHECKIN -> manualCheckin()
                MessageType.QUERY_TASK_STATE -> broadcastState()
                MessageType.SKIP_DATES_CHANGED -> onSkipDatesChanged()
                else -> {}
            }
        }
    }

    private val engineActions by lazy {
        listOf(
            MessageType.RESET_DAILY_TASK.action,
            MessageType.START_DAILY_TASK.action,
            MessageType.STOP_DAILY_TASK.action,
            MessageType.CHECKIN_SUCCESS.action,
            MessageType.MANUAL_CHECKIN.action,
            MessageType.QUERY_TASK_STATE.action,
            MessageType.SKIP_DATES_CHANGED.action
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val name = "${resources.getString(R.string.app_name)}倒计时服务"
        val channel = NotificationChannel(
            "countdown_timer_service_channel", name, NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "Channel for CountDownTimer Service"
        notificationManager.createNotificationChannel(channel)

        BroadcastManager.getDefault().registerReceivers(this, engineActions, engineReceiver)

        // 服务（重启）时恢复当日任务链：进程被杀、开机自启、START_STICKY 重启都会走到这里
        resumeChainIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, notificationBuilder.build())
        if (intent?.action == ACTION_TASK_FIRED) {
            onTaskFired()
        }
        return START_STICKY
    }

    // ═══════════════════════════ 任务链控制 ═════════════════════════════════

    /** 每日重置（ForegroundRunningService 在重置时间广播触发） */
    private fun resetDaily() {
        LogFileManager.writeLog("任务引擎：收到每日重置")
        cancelAllTimers()
        cancelTaskAlarm()
        retryCount = 0
        isSkipEmailSent = false
        SaveKeyValues.putValue(Constant.ENGINE_DATE_KEY, TimeKit.getTodayDate())
        SaveKeyValues.putValue(Constant.ENGINE_LAST_DONE_INDEX_KEY, -1)
        SaveKeyValues.putValue(Constant.ENGINE_LAST_RESULT_KEY, "")
        SaveKeyValues.putValue(Constant.ENGINE_ACTIVE_KEY, true)

        // 请假/星期检查（节假日已在 ForegroundRunningService 侧检查过）
        val (canRun, reason) = checkTodayRunnable(checkHoliday = false)
        if (!canRun) {
            state = EngineState.SKIPPED
            updateNotification("今天休息，已跳过全部打卡")
            broadcastState()
            LogFileManager.writeLog("任务引擎：$reason")
            notifySkipOnce(reason)
            return
        }
        scheduleNextTask()
    }

    /** 手动启动（UI 按钮或远程「启动」指令） */
    private fun startChain() {
        if (isActiveToday()) {
            emailManager.sendEmail(
                "启动任务通知", "任务启动失败，任务已在运行中，请勿重复启动", false
            )
            return
        }
        LogFileManager.writeLog("任务引擎：启动任务链")
        // 跨天残留进度清理：日期变了，已完成索引必须归零
        val storedDate = SaveKeyValues.getValue(Constant.ENGINE_DATE_KEY, "") as String
        if (storedDate != TimeKit.getTodayDate()) {
            SaveKeyValues.putValue(Constant.ENGINE_LAST_DONE_INDEX_KEY, -1)
            SaveKeyValues.putValue(Constant.ENGINE_LAST_RESULT_KEY, "")
        }
        SaveKeyValues.putValue(Constant.ENGINE_DATE_KEY, TimeKit.getTodayDate())
        SaveKeyValues.putValue(Constant.ENGINE_ACTIVE_KEY, true)
        isSkipEmailSent = false

        val tasks = DatabaseWrapper.loadAllTask()
        if (tasks.isEmpty()) {
            LogFileManager.writeLog("任务引擎：任务列表为空，启动失败")
            emailManager.sendEmail("启动任务通知", "任务启动失败，请先添加任务时间点", false)
            return
        }
        emailManager.sendEmail("启动任务通知", "任务启动成功，请注意下次打卡时间", false)
        scheduleNextTask()
    }

    /** 手动停止（UI 按钮或远程「停止」指令） */
    private fun stopChain() {
        if (!isActiveToday()) {
            emailManager.sendEmail(
                "停止任务通知", "任务停止失败，任务已经停止，请勿重复停止", false
            )
            return
        }
        LogFileManager.writeLog("任务引擎：停止任务链")
        cancelAllTimers()
        cancelTaskAlarm()
        retryCount = 0
        currentTaskIndex = -1
        SaveKeyValues.putValue(Constant.ENGINE_ACTIVE_KEY, false)
        state = EngineState.STOPPED
        updateNotification("倒计时任务已停止")
        broadcastState()
        emailManager.sendEmail("停止任务通知", "任务停止成功，请及时打开下次任务", false)
    }

    /** 服务重启后恢复当日任务链（进程死亡/开机场景的关键自愈） */
    private fun resumeChainIfNeeded() {
        if (!isActiveToday()) {
            return
        }
        LogFileManager.writeLog("任务引擎：服务重启，恢复当日任务链")
        CoroutineScope(Dispatchers.IO).launch {
            val (canRun, reason) = checkTodayRunnable(checkHoliday = true)
            withContext(Dispatchers.Main) {
                if (canRun) {
                    // 重新排程：已完成索引之前的任务不会重复执行，
                    // 时间已过的未完成任务会立即执行（相当于白得一次补偿执行）
                    scheduleNextTask()
                } else {
                    state = EngineState.SKIPPED
                    updateNotification("今天休息，已跳过全部打卡")
                    LogFileManager.writeLog("任务引擎恢复时检查：$reason")
                    notifySkipOnce(reason)
                }
            }
        }
    }

    // ═══════════════════════════ 排程与触发 ═════════════════════════════════

    /** 排定下一个任务（已完成索引 + 1） */
    private fun scheduleNextTask() {
        val tasks = DatabaseWrapper.loadAllTask()
        val lastDone = SaveKeyValues.getValue(Constant.ENGINE_LAST_DONE_INDEX_KEY, -1) as Int
        var nextIndex = lastDone + 1

        if (tasks.isEmpty()) {
            LogFileManager.writeLog("任务引擎：任务列表为空，无任务可排程")
            state = EngineState.IDLE
            broadcastState("请先添加任务时间点")
            return
        }

        // 跳过错过的任务：补开机/进程恢复时，计划时间已过且超出宽限期的任务按"错过"处理，
        // 否则会把早上的卡在下午补打，产生异常打卡记录。
        // 宽限期 = max(随机推迟分钟数, 30) + 5 分钟，覆盖随机偏移与短暂的服务中断。
        val legacyRange = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
        val afterMinutes = SaveKeyValues.getValue(
            Constant.RANDOM_AFTER_MINUTES_KEY, legacyRange * 2
        ) as Int
        val graceSeconds = (maxOf(afterMinutes, 30) + 5) * 60
        while (nextIndex < tasks.size && isTaskMissed(tasks[nextIndex], graceSeconds)) {
            val missed = tasks[nextIndex]
            LogFileManager.writeLog("任务引擎：第${nextIndex + 1}个任务（${missed.time}）已过宽限期，按错过处理")
            LogFileManager.writeCheckinLog(
                "错过", "计划时间已过宽限期，自动跳过",
                plannedTime = missed.time, actualTime = null
            )
            nextIndex++
        }
        if (nextIndex > lastDone + 1) {
            // 把错过的任务记入进度，避免重启后重复判定
            SaveKeyValues.putValue(Constant.ENGINE_LAST_DONE_INDEX_KEY, nextIndex - 1)
        }
        if (nextIndex >= tasks.size) {
            allTasksDone()
            return
        }

        val task = tasks[nextIndex]
        val triple = task.diffCurrent()
        val planned = triple.first     // 计划时间
        val actual = triple.second     // 实际执行时间（含随机偏移）
        val diff = triple.third        // 距实际执行时间的秒数（负数 = 立即执行）

        currentTaskIndex = nextIndex
        SaveKeyValues.putValue(Constant.LAST_PLANNED_TIME_KEY, planned)
        SaveKeyValues.putValue(Constant.LAST_ACTUAL_TIME_KEY, actual)

        val taskNo = nextIndex + 1
        val delayNote = if (diff <= 0) "（时间已过，立即执行）" else "（${diff}秒后执行）"
        val logMsg = "第 $taskNo 个任务：计划 $planned → 实际 $actual $delayNote"
        LogFileManager.writeLog(logMsg)
        LogFileManager.writeCheckinLog(
            "待执行", logMsg, plannedTime = planned, actualTime = actual
        )
        emailManager.sendEmail(
            "任务执行通知",
            "准备执行第 $taskNo 个任务，计划时间：$planned，实际时间: $actual$delayNote",
            false
        )

        state = EngineState.COUNTING
        broadcastState()

        // 触发：AlarmManager 精确闹钟（灭屏/Doze 不影响，进程死亡后系统也会唤起接收器）
        val triggerAt = System.currentTimeMillis() + maxOf(diff, 0) * 1000L
        setTaskAlarm(triggerAt)
        // 展示：CountDownTimer 只做通知文本秒级刷新，延迟无影响
        startDisplayTicker(taskNo, maxOf(diff, 0))
    }

    private fun setTaskAlarm(triggerAtMillis: Long) {
        // setAlarmClock 在灭屏/Doze 下也能准时触发，且不需要 SCHEDULE_EXACT_ALARM 权限
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, null)
        alarmManager.setAlarmClock(info, buildAlarmPendingIntent())
        LogFileManager.writeLog("任务引擎：闹钟已设置，${(triggerAtMillis - System.currentTimeMillis()) / 1000}秒后触发")
    }

    private fun cancelTaskAlarm() {
        alarmManager.cancel(buildAlarmPendingIntent())
    }

    /** 固定 intent 的 PendingIntent，保证 set/cancel 指向同一个闹钟 */
    private fun buildAlarmPendingIntent(): PendingIntent {
        val intent = Intent(this, TaskAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 闹钟触发（TaskAlarmReceiver → onStartCommand(ACTION_TASK_FIRED) → 这里） */
    private fun onTaskFired() {
        displayTimer?.cancel()
        displayTimer = null
        if (!isActiveToday()) {
            LogFileManager.writeLog("任务引擎：闹钟触发但任务链已停止或已跨天，忽略")
            return
        }
        if (currentTaskIndex < 0) {
            LogFileManager.writeLog("任务引擎：闹钟触发但无任务上下文，忽略")
            return
        }
        LogFileManager.writeLog("任务引擎：到达执行时间，准备拉起目标应用（第${currentTaskIndex + 1}个任务）")

        // 执行前检查工作日/请假/星期（在子线程中请求节假日 API，避免阻塞主线程）
        CoroutineScope(Dispatchers.IO).launch {
            val (canRun, reason) = checkTodayRunnable(checkHoliday = true)
            LogFileManager.writeLog("任务执行前检查：$reason")
            withContext(Dispatchers.Main) {
                if (canRun) {
                    warmUpNetworkThenOpen()
                } else {
                    // 休息/请假：停住任务链，当天不再执行后续任务，等明天自动重置
                    state = EngineState.SKIPPED
                    updateNotification("今天休息，已跳过全部打卡")
                    broadcastState()
                    LogFileManager.writeLog("休息/请假：停止任务链，当天不再执行后续任务")
                    notifySkipOnce(reason)
                }
            }
        }
    }

    /**
     * 判断任务是否已错过：当前时间超过「计划时间 + 宽限期」
     */
    private fun isTaskMissed(task: DailyTaskBean, graceSeconds: Int): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            val taskDate = sdf.parse("${TimeKit.getTodayDate()} ${task.time}") ?: return false
            val elapsedSeconds = (System.currentTimeMillis() - taskDate.time) / 1000
            elapsedSeconds > graceSeconds
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 打卡前先预热网络（发一次轻量级 HTTP 请求唤醒 Wi-Fi），
     * 最多等待 2 秒，之后无论网络是否就绪都继续拉起目标 App。
     */
    private fun warmUpNetworkThenOpen() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("https://www.baidu.com").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "HEAD"
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                LogFileManager.writeLog("网络预热完成，响应码：$code")
            } catch (e: Exception) {
                LogFileManager.writeLog("网络预热超时或失败（${e.message}），继续执行打卡")
            }
            withContext(Dispatchers.Main) {
                openApplication()
                beginCheckinAttempt()
            }
        }
    }

    // ═══════════════════════════ 打卡尝试（超时/重试/成功） ══════════════════

    private fun beginCheckinAttempt() {
        state = EngineState.WAITING_CHECKIN
        broadcastState()
        startTimeoutTimer()
    }

    private fun startTimeoutTimer() {
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as Int

        timeoutTimer?.cancel()
        timeoutTimer = object : CountDownTimer(time * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val tick = millisUntilFinished / 1000
                BroadcastManager.getDefault().sendBroadcast(
                    this@CountDownTimerService,
                    MessageType.UPDATE_FLOATING_WINDOW_TIME.action,
                    mapOf("tick" to tick)
                )
            }

            override fun onFinish() {
                onCheckinTimeout()
            }
        }
        timeoutTimer?.start()
    }

    /** 超时：回到后台，等待 30 秒后重试；重试 3 次仍失败则任务链终止（保持旧版语义） */
    private fun onCheckinTimeout() {
        val planned = SaveKeyValues.getValue(Constant.LAST_PLANNED_TIME_KEY, "") as String
        val actual = SaveKeyValues.getValue(Constant.LAST_ACTUAL_TIME_KEY, "") as String

        // 只回到主界面，不推进任务链，保留重试逻辑
        backToMainActivityOnly()
        hideFloatingTick()

        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            state = EngineState.RETRY_WAIT
            broadcastState()
            val msg = "打卡超时（第 $retryCount 次），${RETRY_INTERVAL_SECONDS} 秒后自动重试..."
            LogFileManager.writeLog(msg)
            LogFileManager.writeCheckinLog(
                "超时-第${retryCount}次重试等待", msg,
                plannedTime = planned.ifBlank { null },
                actualTime = actual.ifBlank { null }
            )

            retryWaitTimer?.cancel()
            retryWaitTimer = object : CountDownTimer(RETRY_INTERVAL_SECONDS * 1000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    val retryMsg = "开始第 $retryCount 次重试打卡"
                    LogFileManager.writeLog(retryMsg)
                    LogFileManager.writeCheckinLog(
                        "超时-第${retryCount}次重试启动", retryMsg,
                        plannedTime = planned.ifBlank { null },
                        actualTime = actual.ifBlank { null }
                    )
                    openApplication()
                    startTimeoutTimer()
                }
            }
            retryWaitTimer?.start()
        } else {
            // 已达到最大重试次数，彻底失败，任务链终止，等明天重置或手动重启
            state = EngineState.FAILED
            val failMsg = "打卡失败：已超时重试 $MAX_RETRY_COUNT 次，均未收到打卡成功通知"
            LogFileManager.writeLog(failMsg)
            LogFileManager.writeCheckinLog(
                "失败-已重试${MAX_RETRY_COUNT}次", failMsg,
                plannedTime = planned.ifBlank { null },
                actualTime = actual.ifBlank { null }
            )
            if (currentTaskIndex >= 0) {
                SaveKeyValues.putValue(
                    Constant.ENGINE_LAST_RESULT_KEY, "第${currentTaskIndex + 1}个任务打卡失败"
                )
            } else {
                // 手动打卡失败：不污染任务链状态，恢复到手动打卡前的状态
                SaveKeyValues.putValue(Constant.ENGINE_LAST_RESULT_KEY, "手动打卡失败")
                state = stateBeforeManual
            }
            updateNotification("打卡失败，请检查目标应用")
            broadcastState()
            emailManager.sendEmail("打卡失败通知", failMsg, false)
        }
    }

    /** 打卡成功（NotificationMonitorService 广播触发） */
    private fun onCheckinSuccess() {
        // 只有存在进行中的打卡尝试时才处理，否则是重复/无关通知，直接忽略
        if (state != EngineState.WAITING_CHECKIN && state != EngineState.RETRY_WAIT) {
            LogFileManager.writeLog("任务引擎：收到打卡成功，但当前无打卡尝试（state=$state），忽略")
            return
        }
        timeoutTimer?.cancel()
        timeoutTimer = null
        retryWaitTimer?.cancel()
        retryWaitTimer = null
        retryCount = 0
        hideFloatingTick()

        if (currentTaskIndex >= 0 && isActiveToday()) {
            // 与任务关联的成功：记录进度并推进到下一个任务
            val doneIndex = currentTaskIndex
            LogFileManager.writeLog("任务引擎：第${doneIndex + 1}个任务打卡成功，推进到下一个任务")
            SaveKeyValues.putValue(Constant.ENGINE_LAST_DONE_INDEX_KEY, doneIndex)
            val actual = SaveKeyValues.getValue(Constant.LAST_ACTUAL_TIME_KEY, "") as String
            SaveKeyValues.putValue(
                Constant.ENGINE_LAST_RESULT_KEY, "第${doneIndex + 1}个任务 $actual 打卡成功"
            )
            scheduleNextTask()
        } else {
            // 纯手动打卡成功：不影响任务链，恢复到手动打卡前的状态
            LogFileManager.writeLog("任务引擎：手动打卡成功")
            SaveKeyValues.putValue(Constant.ENGINE_LAST_RESULT_KEY, "手动打卡成功")
            state = stateBeforeManual
            updateNotification("手动打卡成功")
            broadcastState()
        }
    }

    /** 远程「打卡」指令（NotificationMonitorService 广播触发） */
    private fun manualCheckin() {
        when {
            !isActiveToday() -> {
                // 任务链未布防：纯手动打卡，成功/失败都不影响任务链
                LogFileManager.writeLog("任务引擎：手动打卡（任务链未启动）")
                stateBeforeManual = state
                currentTaskIndex = -1
                warmUpNetworkThenOpen()
            }

            state == EngineState.WAITING_CHECKIN || state == EngineState.RETRY_WAIT -> {
                // 已有进行中的打卡尝试：直接再拉一次目标应用
                LogFileManager.writeLog("任务引擎：手动打卡（已有打卡尝试进行中，直接唤起）")
                openApplication()
            }

            state == EngineState.COUNTING -> {
                // 有任务正在倒计时：视为立即执行当前任务
                LogFileManager.writeLog("任务引擎：手动打卡，立即执行当前任务（第${currentTaskIndex + 1}个）")
                cancelTaskAlarm()
                displayTimer?.cancel()
                displayTimer = null
                warmUpNetworkThenOpen()
            }

            else -> {
                // DONE/STOPPED/FAILED/SKIPPED/IDLE：纯手动打卡，不关联任何任务
                LogFileManager.writeLog("任务引擎：手动打卡（state=$state）")
                stateBeforeManual = state
                currentTaskIndex = -1
                warmUpNetworkThenOpen()
            }
        }
    }

    // ═══════════════════════════ 请假日期变化 ═══════════════════════════════

    private fun onSkipDatesChanged() {
        if (!isActiveToday()) {
            return
        }
        if (SkipDates.isTodaySkipped()) {
            // 今日变为请假：取消全部排程与进行中的尝试
            LogFileManager.writeLog("任务引擎：今日加入请假列表，取消今日剩余任务")
            val wasAttempting = (state == EngineState.WAITING_CHECKIN || state == EngineState.RETRY_WAIT)
            cancelAllTimers()
            cancelTaskAlarm()
            retryCount = 0
            currentTaskIndex = -1
            state = EngineState.SKIPPED
            if (wasAttempting) {
                backToMainActivityOnly()
            }
            updateNotification("今日已请假，跳过打卡")
            broadcastState()
        } else if (state == EngineState.SKIPPED) {
            // 今日销假：恢复任务链前重新检查（SKIPPED 也可能是节假日导致的，节假日不能因销假而恢复）
            LogFileManager.writeLog("任务引擎：今日销假，重新评估任务链")
            CoroutineScope(Dispatchers.IO).launch {
                val (canRun, reason) = checkTodayRunnable(checkHoliday = true)
                withContext(Dispatchers.Main) {
                    if (canRun) {
                        scheduleNextTask()
                    } else {
                        LogFileManager.writeLog("任务引擎：销假后仍不满足打卡条件：$reason")
                        updateNotification("今天休息，已跳过全部打卡")
                        notifySkipOnce(reason)
                    }
                }
            }
        }
    }

    // ═══════════════════════════ 公共工具 ═══════════════════════════════════

    /** 任务链是否已布防且属于今天 */
    private fun isActiveToday(): Boolean {
        val active = SaveKeyValues.getValue(Constant.ENGINE_ACTIVE_KEY, false) as Boolean
        val date = SaveKeyValues.getValue(Constant.ENGINE_DATE_KEY, "") as String
        return active && date == TimeKit.getTodayDate()
    }

    /**
     * 今天是否可以执行打卡：请假日期 → 打卡星期 →（可选）节假日三级检查
     * @param checkHoliday 为 false 时跳过节假日网络请求（调用方已检查过的场景）
     */
    private fun checkTodayRunnable(checkHoliday: Boolean): Pair<Boolean, String> {
        if (SkipDates.isTodaySkipped()) {
            return Pair(false, "今天在请假日期列表中，跳过打卡任务")
        }
        if (!WeekSchedule.isTodayEnabled()) {
            return Pair(false, "今天是${WeekSchedule.todayName()}，不在打卡星期设置中，跳过打卡任务")
        }
        if (checkHoliday) {
            return HolidayChecker.shouldWorkToday()
        }
        return Pair(true, "")
    }

    /** 休息/请假邮件当天只发一次 */
    private fun notifySkipOnce(reason: String) {
        if (!isSkipEmailSent) {
            isSkipEmailSent = true
            emailManager.sendEmail("打卡任务通知", reason, false)
        }
    }

    private fun allTasksDone() {
        LogFileManager.writeLog("任务引擎：今日任务已全部执行完毕")
        state = EngineState.DONE
        currentTaskIndex = -1
        updateNotification("当天所有任务已执行完毕")
        broadcastState()
        emailManager.sendEmail("任务状态通知", "今日任务已全部执行完毕", false)
    }

    private fun cancelAllTimers() {
        displayTimer?.cancel()
        displayTimer = null
        timeoutTimer?.cancel()
        timeoutTimer = null
        retryWaitTimer?.cancel()
        retryWaitTimer = null
    }

    private fun startDisplayTicker(taskNo: Int, seconds: Int) {
        displayTimer?.cancel()
        displayTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val s = (millisUntilFinished / 1000).toInt()
                updateNotification("${s.formatTime()}后执行第${taskNo}个任务")
            }

            override fun onFinish() {}
        }.apply { start() }
    }

    private fun updateNotification(text: String) {
        val notification = notificationBuilder.apply {
            setContentText(text)
        }.build()
        notificationManager.notify(notificationId, notification)
    }

    /** 打卡结束（成功/超时）时隐藏悬浮窗倒计时 */
    private fun hideFloatingTick() {
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.UPDATE_FLOATING_WINDOW_TIME.action, mapOf("tick" to 0L)
        )
    }

    /** 向 UI 广播当前状态快照 */
    private fun broadcastState(message: String? = null) {
        val msg = message ?: when (state) {
            EngineState.IDLE -> ""
            EngineState.COUNTING -> "准备执行第 ${currentTaskIndex + 1} 个任务"
            EngineState.WAITING_CHECKIN -> "正在执行打卡..."
            EngineState.RETRY_WAIT -> "打卡超时，等待自动重试..."
            EngineState.DONE -> "当天所有任务已执行完毕"
            EngineState.STOPPED -> "任务已停止"
            EngineState.SKIPPED -> "今日休息/请假，已跳过打卡"
            EngineState.FAILED -> "打卡失败，请检查"
        }
        BroadcastManager.getDefault().sendBroadcast(
            this,
            MessageType.TASK_STATE_CHANGED.action,
            mapOf(
                "state" to state.name,
                "taskIndex" to currentTaskIndex,
                "actualTime" to (SaveKeyValues.getValue(Constant.LAST_ACTUAL_TIME_KEY, "") as String),
                "message" to msg,
                "active" to isActiveToday()
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        engineActions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(this, it)
        }
        cancelAllTimers()
        cancelTaskAlarm()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
