package com.pengxh.daily.app

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.room.Room.databaseBuilder
import com.pengxh.daily.app.sqlite.DailyTaskDataBase
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.AppConfigManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.tencent.bugly.crashreport.CrashReport


/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 13:19
 */
class DailyTaskApplication : Application() {

    companion object {
        private lateinit var application: DailyTaskApplication

        fun get(): DailyTaskApplication = application

        internal fun initApplication(app: DailyTaskApplication) {
            application = app
        }
    }

    lateinit var dataBase: DailyTaskDataBase

    override fun onCreate() {
        super.onCreate()
        initApplication(this)
        SaveKeyValues.initSharedPreferences(this)
        LogFileManager.initLogFile(this)
        // 加载持久化配置（升级后自动恢复用户设置，含版本迁移）
        AppConfigManager.init(this)

        val isDebugMode = BuildConfig.DEBUG
        CrashReport.initCrashReport(this, "ecbdc9baf5", isDebugMode)

        // 崩溃自愈：未捕获异常时自动重启应用（打卡无人值守，崩溃不能静默致死）
        installCrashRestartHandler()

        dataBase = databaseBuilder(this, DailyTaskDataBase::class.java, "DailyTask.db")
            .allowMainThreadQueries()
            .build()
    }

    /**
     * 崩溃自愈：捕获未处理异常，写日志后自动重启 MainActivity。
     *
     * 防崩溃死循环：两次崩溃重启间隔小于 60 秒时放弃重启（说明启动即崩），
     * 异常继续委托给 Bugly 的上报处理器。
     */
    private fun installCrashRestartHandler() {
        // Bugly 已初始化，保留其处理器用于崩溃上报
        val buglyHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val now = System.currentTimeMillis()
                val lastRestart = SaveKeyValues.getValue(
                    Constant.LAST_CRASH_RESTART_TS_KEY, 0L
                ) as Long
                LogFileManager.writeLog(
                    "未捕获异常（${throwable.javaClass.simpleName}: ${throwable.message}），应用即将自重启"
                )
                if (now - lastRestart > 60_000L) {
                    SaveKeyValues.putValue(Constant.LAST_CRASH_RESTART_TS_KEY, now)
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                } else {
                    LogFileManager.writeLog("60秒内重复崩溃，放弃自重启以防止崩溃死循环")
                }
            } catch (e: Exception) {
                // 自愈失败不影响崩溃上报
            } finally {
                // 委托给 Bugly 完成崩溃上报并结束进程
                if (buglyHandler != null) {
                    buglyHandler.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }
}