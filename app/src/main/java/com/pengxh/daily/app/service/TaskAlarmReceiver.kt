package com.pengxh.daily.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengxh.daily.app.utils.LogFileManager

/**
 * 任务闹钟接收器（清单注册，进程死亡后系统也能唤起）。
 *
 * 引擎用 AlarmManager.setAlarmClock 设置的闹钟到点后触发本接收器，
 * 再以前台服务方式拉起任务引擎执行打卡。
 * setAlarmClock 的 PendingIntent 执行期间，系统豁免后台启动前台服务的限制。
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LogFileManager.writeLog("TaskAlarmReceiver: 任务闹钟触发，拉起任务引擎")
        try {
            val serviceIntent = Intent(context, CountDownTimerService::class.java).apply {
                action = CountDownTimerService.ACTION_TASK_FIRED
            }
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // 极端情况下（如后台 FGS 限制）拉起失败，依赖 START_STICKY 的服务重启自愈
            LogFileManager.writeLog("TaskAlarmReceiver: 拉起任务引擎失败（${e.javaClass.simpleName}）")
        }
    }
}
