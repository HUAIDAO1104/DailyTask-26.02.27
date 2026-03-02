package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HolidayChecker
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
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
    private var isTaskReset = false
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
                    }
                }
            }
        }
    }

    private fun resetTask() {
        if (!isTaskReset) {
            isTaskReset = true

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
            registerReceiver(systemBroadcastReceiver, filter, RECEIVER_EXPORTED)
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
                    val hour = intent?.getIntExtra("hour", 0) as Int
                    startResetTaskTimer(hour)
                }
            })

        // 启动重置任务计时器
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        startResetTaskTimer(hour)
    }

    private fun startResetTaskTimer(hour: Int) {
        if (isTimerRunning) return  // 防止重复启动
        val currentDiffSeconds = resetTaskSeconds(hour)

        // 先取消之前的计时器
        taskTimer?.cancel()
        taskTimer = object : CountDownTimer(currentDiffSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                val message = String.format(
                    Locale.getDefault(), "%s后刷新每日任务", seconds.formatTime()
                )
                BroadcastManager.getDefault().sendBroadcast(
                    this@ForegroundRunningService,
                    MessageType.UPDATE_RESET_TICK_TIME.action,
                    mapOf("message" to message)
                )
            }

            override fun onFinish() {
                isTaskReset = false
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
        val restartIntent = Intent(applicationContext, ForegroundRunningService::class.java)
        startForegroundService(restartIntent)
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