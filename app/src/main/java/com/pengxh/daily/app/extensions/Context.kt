package com.pengxh.daily.app.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog

/**
 * 检测通知监听服务是否被授权
 * */
fun Context.notificationEnable(): Boolean {
    val packages = NotificationManagerCompat.getEnabledListenerPackages(this)
    return packages.contains(packageName)
}

/**
 * 判断指定包名的应用是否存在
 */
fun Context.isApplicationExist(packageName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
        false
    }
}

/**
 * 打开指定包名的apk。
 * 打开后的打卡超时/重试由任务引擎（CountDownTimerService）管理，
 * 调用方无需再做任何处理。
 */
fun Context.openApplication() {
    val targetApp = Constant.getTargetApp()
    if (!isApplicationExist(targetApp)) {
        AlertMessageDialog.Builder()
            .setContext(this)
            .setTitle("温馨提醒")
            .setMessage("手机没有安装指定的目标应用软件，无法执行任务")
            .setPositiveButton("知道了")
            .setOnDialogButtonClickListener(object :
                AlertMessageDialog.OnDialogButtonClickListener {
                override fun onConfirmClick() {

                }
            }).build().show()
        return
    }

    // 跳转目标应用
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage(targetApp)
    }
    val activities = packageManager.queryIntentActivities(intent, 0)
    if (activities.isNotEmpty()) {
        val info = activities.first()
        intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        startActivity(intent)
    }
}

/**
 * 回到主界面并推进到下一个任务。
 * 仅在打卡成功后调用，会发送 CANCEL_COUNT_DOWN_TIMER 广播触发任务链推进。
 */
fun Context.backToMainActivity() {
    BroadcastManager.getDefault().sendBroadcast(this, MessageType.CANCEL_COUNT_DOWN_TIMER.action)
    val backToHome = SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean
    if (backToHome) {
        //模拟点击Home键
        val home = Intent(Intent.ACTION_MAIN).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            addCategory(Intent.CATEGORY_HOME)
        }
        startActivity(home)
        Handler(Looper.getMainLooper()).postDelayed({
            launchMainActivity(autoMask = false)
        }, 2000)
    } else {
        launchMainActivity(autoMask = false)
    }
}

/**
 * 超时/重试场景：仅退出钉钉回到后台，绝对不重建 MainActivity。
 *
 * 重建 MainActivity（FLAG_CLEAR_TASK）会销毁旧实例，导致：
 *   - retryCount 归零
 *   - retryWaitTimer 所在的旧实例被销毁，重试永远不会触发
 *   - CountDownTimerService 的 bind 断开，倒计时被取消
 * 因此超时时只需要按 Home 键退出钉钉，让 MainActivity 原地等待重试即可。
 *
 * @param autoMask 保留参数（超时场景传 false，打卡成功场景不走此函数）
 */
fun Context.backToMainActivityOnly(autoMask: Boolean = false) {
    // 只通知 MainActivity 记日志，不做任何界面切换
    BroadcastManager.getDefault().sendBroadcast(this, MessageType.BACK_TO_MAIN_ONLY.action)
    // 按 Home 键把钉钉退到后台，让 MainActivity 保持原实例继续运行
    val home = Intent(Intent.ACTION_MAIN).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        addCategory(Intent.CATEGORY_HOME)
    }
    startActivity(home)
}

private fun Context.launchMainActivity(autoMask: Boolean = false) {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra("autoMask", autoMask)
    }
    startActivity(intent)
}