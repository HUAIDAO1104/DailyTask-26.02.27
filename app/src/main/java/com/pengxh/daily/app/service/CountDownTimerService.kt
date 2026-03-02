package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HolidayChecker
import com.pengxh.daily.app.utils.LogFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * APP倒计时服务，解决手机灭屏后倒计时会出现延迟的问题
 * */
class CountDownTimerService : Service() {
    private val binder by lazy { LocaleBinder() }
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
    private var countDownTimer: CountDownTimer? = null
    private var isTimerRunning = false
    private val emailManager by lazy { EmailManager(this) }

    // 防止休息日多个任务到点时重复发邮件的标志位，每天任务重置时清空
    private var isHolidayEmailSent = false

    inner class LocaleBinder : Binder() {
        fun getService(): CountDownTimerService = this@CountDownTimerService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        val name = "${resources.getString(R.string.app_name)}倒计时服务"
        val channel = NotificationChannel(
            "countdown_timer_service_channel", name, NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "Channel for CountDownTimer Service"
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, notificationBuilder.build())
        return START_STICKY
    }

    fun startCountDown(taskIndex: Int, seconds: Int) {
        if (isTimerRunning) {
            countDownTimer?.cancel()
            countDownTimer = null
            isTimerRunning = false
            LogFileManager.writeLog("startCountDown: 第${taskIndex}个任务重复执行，取消之前的任务")
        }
        LogFileManager.writeLog("startCountDown: 倒计时任务开始，执行第${taskIndex}个任务")
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                val notification = notificationBuilder.apply {
                    setContentText("${seconds.formatTime()}后执行第${taskIndex}个任务")
                }.build()
                notificationManager.notify(notificationId, notification)
            }

            override fun onFinish() {
                isTimerRunning = false
                // 打卡前先检查今天是否是工作日（在子线程中请求 API，避免阻塞主线程）
                CoroutineScope(Dispatchers.IO).launch {
                    val (shouldWork, reason) = HolidayChecker.shouldWorkToday()
                    LogFileManager.writeLog("节假日检查结果：$reason")

                    withContext(Dispatchers.Main) {
                        if (shouldWork) {
                            // 工作日或调休补班：先预热网络，再打开目标应用
                            warmUpNetworkThenOpen()
                        } else {
                            // 周末或法定节假日：直接停住，什么都不做
                            // 【修复】不再发送 CANCEL_COUNT_DOWN_TIMER 广播。
                            // 原来发广播会触发 MainActivity.dailyTaskRunnable 继续执行下一个任务，
                            // 导致当天每个任务倒计时结束后都反复走这里，形成无效循环。
                            // 正确做法是直接停住，让任务链自然结束，等明天自动重置。
                            val notification = notificationBuilder.apply {
                                setContentText("今天休息，已跳过全部打卡")
                            }.build()
                            notificationManager.notify(notificationId, notification)
                            LogFileManager.writeLog("休息日：停止任务链，当天不再执行后续任务")
                            // 发邮件通知用户今天跳过打卡
                            // HolidayChecker 有当天缓存，多个任务到点时只有第一次会实际请求网络，
                            // 但邮件只需发一次，所以用 isHolidayEmailSent 标志位控制
                            if (!isHolidayEmailSent) {
                                isHolidayEmailSent = true
                                emailManager.sendEmail("打卡任务通知", reason, false)
                            }
                        }
                    }
                }
            }
        }.apply {
            start()
        }
        isTimerRunning = true
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
                openApplication(true)
            }
        }
    }

    fun updateDailyTaskState() {
        val notification = notificationBuilder.apply {
            setContentText("当天所有任务已执行完毕")
        }.build()
        notificationManager.notify(notificationId, notification)
        isTimerRunning = false
    }

    /**
     * 每天任务重置时调用，清空休息日邮件发送标志，为新的一天做准备
     */
    fun resetDailyState() {
        isHolidayEmailSent = false
        LogFileManager.writeLog("resetDailyState: 每日状态已重置")
    }

    fun cancelCountDown() {
        if (isTimerRunning) {
            countDownTimer?.cancel()
            countDownTimer = null
            val notification = notificationBuilder.apply {
                setContentText("倒计时任务已停止")
            }.build()
            notificationManager.notify(notificationId, notification)
            isTimerRunning = false
        }
        LogFileManager.writeLog("cancelCountDown: 倒计时任务取消")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelCountDown()
    }
}