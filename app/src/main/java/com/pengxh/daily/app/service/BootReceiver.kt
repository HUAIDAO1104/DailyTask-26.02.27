package com.pengxh.daily.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengxh.daily.app.utils.LogFileManager

/**
 * 开机自启接收器。
 *
 * 手机重启后拉起两个前台服务：
 *   - ForegroundRunningService：保活 + 每日重置（含漏过重置的补偿）；
 *   - CountDownTimerService（任务引擎）：自动恢复当日任务链。
 *
 * BOOT_COMPLETED 属于系统豁免场景，允许在后台启动前台服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        LogFileManager.writeLog("BootReceiver: 收到开机广播，启动前台服务")
        try {
            context.startForegroundService(
                Intent(context, ForegroundRunningService::class.java)
            )
        } catch (e: Exception) {
            LogFileManager.writeLog("BootReceiver: 启动保活服务失败（${e.javaClass.simpleName}）")
        }
        try {
            context.startForegroundService(
                Intent(context, CountDownTimerService::class.java)
            )
        } catch (e: Exception) {
            LogFileManager.writeLog("BootReceiver: 启动任务引擎失败（${e.javaClass.simpleName}）")
        }
    }
}
