package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HolidayChecker
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale


/**
 * APP前台服务，降低APP被系统杀死的可能性
 * */
class ForegroundRunningService : Service() {
    private val kTag = "ForegroundRunningService"
    private val notificationId = Int.MAX_VALUE
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, "foreground_running_service_channel").apply {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentText(Constant.FOREGROUND_RUNNING_SERVICE_TITLE)
            setPriority(NotificationCompat.PRIORITY_HIGH) // 设置通知优先级
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setShowWhen(true)
            setSound(null) // 禁用声音
            setVibrate(null) // 禁用振动
        }
    }
    private val emailManager by lazy { EmailManager(this) }
    private var isTimerRunning = false
    private var taskTimer: CountDownTimer? = null

    private val systemBroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    // 监听时间，系统级广播，每分钟触发一次。
                    if (it == Intent.ACTION_TIME_TICK) {
                        val hour = SaveKeyValues.getValue(
                            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
                        ) as Int
                        val calendar = Calendar.getInstance()
                        if (calendar.get(Calendar.HOUR_OF_DAY) == hour) {
                            resetTask()
                        }
                        // 低电量预警检查（每天最多一次）
                        checkLowBattery()
                    }
                }
            }
        }
    }

    private fun resetTask() {
        // 用持久化日期防止重复重置：进程重启后内存标志会丢失，日期判断不会
        val today = TimeKit.getTodayDate()
        val lastResetDate = SaveKeyValues.getValue(Constant.LAST_RESET_DATE_KEY, "") as String
        if (lastResetDate == today) {
            return
        }
        SaveKeyValues.putValue(Constant.LAST_RESET_DATE_KEY, today)

        // 在子线程中查询节假日，避免阻塞主线程
        CoroutineScope(Dispatchers.IO).launch {
            val (shouldWork, holidayReason) = HolidayChecker.shouldWorkToday()
            LogFileManager.writeLog("每日任务重置时节假日检查：$holidayReason")

            var message: String
            if (!shouldWork) {
                // 今天是休息日（周末或法定节假日），不启动打卡任务
                message = "$holidayReason，今日不执行打卡任务。"
                LogFileManager.writeLog(message)
                emailManager.sendEmail("循环任务状态通知", message, false)
            } else if (SaveKeyValues.getValue(Constant.TASK_AUTO_START_KEY, true) as Boolean) {
                // 今天是工作日，且设置了自动启动，重置并执行任务
                BroadcastManager.getDefault().sendBroadcast(
                    this@ForegroundRunningService, MessageType.RESET_DAILY_TASK.action
                )
                message = "到达任务计划时间，重置每日任务。$holidayReason"
                LogFileManager.writeLog(message)
                emailManager.sendEmail("循环任务状态通知", message, false)
            } else {
                message = "每日任务已手动停止，不再自动重置！如需恢复，可通过远程消息发送【开始循环】指令。"
                LogFileManager.writeLog(message)
                emailManager.sendEmail("循环任务状态通知", message, false)
            }
        }

        // 重置任务计时器（等待下一天）
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        startResetTaskTimer(hour)
    }

    /**
     * 低电量预警：电量 ≤20% 且未在充电时发邮件提醒，每天最多一次。
     * 打卡手机长期放置，低电关机是打卡失败的常见原因之一。
     */
    private fun checkLowBattery() {
        val today = TimeKit.getTodayDate()
        if (SaveKeyValues.getValue(Constant.LOW_BATTERY_ALERT_DATE_KEY, "") as String == today) {
            return
        }
        // 读取粘性广播获取电池状态（null receiver 只是查询，不需要注册标志）
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) {
            return
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val percent = level * 100 / scale
        if (percent <= 20 && !charging) {
            SaveKeyValues.putValue(Constant.LOW_BATTERY_ALERT_DATE_KEY, today)
            LogFileManager.writeLog("电量低（$percent%）且未在充电，发送预警邮件")
            emailManager.sendEmail(
                "低电量预警",
                "打卡手机电量仅剩 $percent% 且未在充电，请及时充电，避免关机导致打卡失败！",
                false
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val name = "${resources.getString(R.string.app_name)}前台服务"
        val channel = NotificationChannel(
            "foreground_running_service_channel", name, NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for Foreground Running Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notification = notificationBuilder.build()
        startForeground(notificationId, notification)

        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // TIME_TICK 是系统广播，NOT_EXPORTED 依然能正常收到，且可避免被其他应用伪造触发
            registerReceiver(systemBroadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemBroadcastReceiver, filter)
        }

        // 监听重置任务时间
        BroadcastManager.getDefault().registerReceiver(
            this,
            MessageType.SET_RESET_TASK_TIME.action,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // 重置任务计时器
                    val hour = intent?.getIntExtra("hour", Constant.DEFAULT_RESET_HOUR)
                        ?: Constant.DEFAULT_RESET_HOUR
                    startResetTaskTimer(hour)
                }
            })

        // 启动重置任务计时器
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        startResetTaskTimer(hour)

        // 补偿：如果今天的重置被错过（手机关机/服务被杀），且当前已过重置时间，立即补一次
        val lastResetDate = SaveKeyValues.getValue(Constant.LAST_RESET_DATE_KEY, "") as String
        if (lastResetDate != TimeKit.getTodayDate()
            && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= hour
        ) {
            LogFileManager.writeLog("检测到今天的任务重置被错过（关机或服务重启），立即补偿重置")
            resetTask()
        }
    }

    private fun startResetTaskTimer(hour: Int) {
        // 不能根据 isTimerRunning 直接 return：用户修改重置时间后必须取消旧计时器并按新时间重启，
        // 否则新设置要等旧计时器跑完（最长可能是一整天）才生效
        val currentDiffSeconds = resetTaskSeconds(hour)

        // 先取消之前的计时器
        taskTimer?.cancel()
        taskTimer = object : CountDownTimer(currentDiffSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                val message = String.format(
                    Locale.getDefault(), "%02d:%02d:%02d 后刷新",
                    seconds / 3600, (seconds % 3600) / 60, seconds % 60
                )
                BroadcastManager.getDefault().sendBroadcast(
                    this@ForegroundRunningService,
                    MessageType.UPDATE_RESET_TICK_TIME.action,
                    mapOf("message" to message)
                )
            }

            override fun onFinish() {
                isTimerRunning = false
                taskTimer = null
            }
        }
        isTimerRunning = true
        taskTimer?.start()
    }

    private fun resetTaskSeconds(hour: Int): Int {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        // 设置今天的计划时间
        val todayTargetMillis = calendar.clone() as Calendar
        todayTargetMillis.set(Calendar.HOUR_OF_DAY, hour)
        todayTargetMillis.set(Calendar.MINUTE, 0)
        todayTargetMillis.set(Calendar.SECOND, 0)
        todayTargetMillis.set(Calendar.MILLISECOND, 0)

        // 根据当前时间决定计算哪一天的计划时间
        val targetMillis = if (currentHour < hour) {
            // 今天还没到计划时间
            todayTargetMillis.timeInMillis
        } else {
            // 今天已经过了计划时间，计算明天的
            todayTargetMillis.add(Calendar.DATE, 1)
            todayTargetMillis.timeInMillis
        }

        val delta = (targetMillis - System.currentTimeMillis()) / 1000
        return delta.toInt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 确保服务被系统杀死后自动重启
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务列表划掉 App 时，重新拉起前台服务以保活
        super.onTaskRemoved(rootIntent)
        LogFileManager.writeLog("ForegroundRunningService: 检测到任务被移除，重新拉起服务")
        try {
            val restartIntent = Intent(applicationContext, ForegroundRunningService::class.java)
            startForegroundService(restartIntent)
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限时可能抛
            // ForegroundServiceStartNotAllowedException / IllegalStateException，不能直接崩溃
            LogFileManager.writeLog("ForegroundRunningService: 重新拉起服务失败（${e.javaClass.simpleName}），等待系统调度")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BroadcastManager.getDefault().unregisterReceiver(
            this, MessageType.SET_RESET_TASK_TIME.action
        )
        unregisterReceiver(systemBroadcastReceiver)
        taskTimer?.cancel()
        taskTimer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}