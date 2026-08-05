package com.pengxh.daily.app.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivitySettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.UpdateManager
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val context = this

    private val actions by lazy {
        listOf(
            MessageType.NOTICE_LISTENER_CONNECTED.action,
            MessageType.NOTICE_LISTENER_DISCONNECTED.action
        )
    }
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                when (MessageType.fromAction(it)) {
                    MessageType.NOTICE_LISTENER_CONNECTED -> {
                        binding.tipsView.text = "通知监听服务状态查询中，请稍后"
                        binding.tipsView.setTextColor(
                            R.color.theme_color.convertColor(this@SettingsActivity)
                        )
                        binding.noticeSwitch.isChecked = true
                        binding.tipsView.visibility = View.GONE
                    }

                    MessageType.NOTICE_LISTENER_DISCONNECTED -> {
                        binding.tipsView.text = "通知监听服务未开启，无法监听打卡通知"
                        binding.tipsView.setTextColor(Color.RED)
                        binding.noticeSwitch.isChecked = false
                        binding.tipsView.visibility = View.VISIBLE
                    }

                    else -> {}
                }
            }
        }
    }

    override fun initViewBinding(): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        BroadcastManager.getDefault().registerReceivers(this, actions, broadcastReceiver)

        binding.appVersion.text = BuildConfig.VERSION_NAME
        if (notificationEnable()) {
            turnOnNotificationMonitorService()
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.emailConfigLayout.setOnClickListener {
            navigatePageTo<EmailConfigActivity>()
        }

        binding.taskConfigLayout.setOnClickListener {
            navigatePageTo<TaskConfigActivity>()
        }

        binding.noticeSwitch.setOnClickListener {
            notificationSettingLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.openTestLayout.setOnClickListener {
            openApplication()
        }

        binding.batteryOptLayout.setOnClickListener {
            // 跳转系统「忽略电池优化」设置页，引导用户将本应用设为不优化。
            // 用系统设置列表页而非 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，
            // 不需要声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                "无法打开电池优化设置页，请手动前往系统设置".show(this)
            }
        }

        binding.gestureDetectorSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.GESTURE_DETECTOR_KEY, isChecked)
        }

        binding.backToHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.BACK_TO_HOME_KEY, isChecked)
        }

        binding.notificationLayout.setOnClickListener {
            navigatePageTo<NoticeRecordActivity>()
        }

        binding.vendorPermissionLayout.setOnClickListener {
            openVendorSettings()
        }

        binding.logLayout.setOnClickListener {
            navigatePageTo<LogViewerActivity>()
        }

        binding.versionLayout.setOnClickListener {
            UpdateManager.check(this, manual = true)
        }

        binding.statsLayout.setOnClickListener {
            showMonthStats()
        }

        binding.introduceLayout.setOnClickListener {
            navigatePageTo<QuestionAndAnswerActivity>()
        }
    }

    /**
     * 跳转厂商自启动/后台权限设置页：依次尝试小米、华为、OPPO、vivo 的
     * 已知页面，全部失败则退到本应用详情页，由用户自行寻找相关设置。
     */
    private fun openVendorSettings() {
        val vendorIntents = listOf(
            // 小米 MIUI 自启动管理
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            // 华为 启动管理
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ),
            // OPPO 自启动管理
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            // vivo 自启动/白名单
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
        )
        for (intent in vendorIntents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // 该厂商页面不存在，尝试下一个
            }
        }
        // 兜底：本应用详情页
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        } catch (e: Exception) {
            "无法打开系统设置页，请手动前往系统设置".show(this)
        }
    }

    /** 本月打卡统计：解析当月打卡日志文件，统计成功/失败/重试次数 */
    private fun showMonthStats() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val month = SimpleDateFormat("yyyyMM", Locale.CHINA).format(Date())
            val file = File(dir, "checkin_log_$month.txt")
            if (dir == null || !file.exists()) {
                "本月还没有打卡记录".show(this)
                return
            }
            var success = 0
            var fail = 0
            var retry = 0
            var lastSuccess = ""
            file.forEachLine { line ->
                when {
                    line.contains("打卡成功") -> {
                        success++
                        lastSuccess = line
                    }

                    line.contains("失败-已重试") -> fail++
                    line.contains("超时-第") -> retry++
                }
            }
            val message = buildString {
                append("打卡成功：${success}次\n")
                append("打卡失败：${fail}次\n")
                append("超时重试：${retry}次")
                if (lastSuccess.isNotBlank()) {
                    append("\n\n最近一次成功：\n$lastSuccess")
                }
            }
            AlertMessageDialog.Builder()
                .setContext(this)
                .setTitle("本月打卡统计")
                .setMessage(message)
                .setPositiveButton("知道了")
                .setOnDialogButtonClickListener(object :
                    AlertMessageDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick() {}
                }).build().show()
        } catch (e: Exception) {
            "读取统计信息失败：${e.message}".show(this)
        }
    }

    private val notificationSettingLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (notificationEnable()) {
                turnOnNotificationMonitorService()
            }
        }

    override fun onResume() {
        super.onResume()
        binding.emailSwitch.isChecked = DatabaseWrapper.loadAll().isNotEmpty()
        binding.gestureDetectorSwitch.isChecked =
            SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean
        binding.backToHomeSwitch.isChecked =
            SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean

        if (notificationEnable()) {
            binding.tipsView.text = "通知监听服务状态查询中，请稍后"
            binding.tipsView.setTextColor(R.color.theme_color.convertColor(this))
            lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                if (notificationEnable()) {
                    binding.noticeSwitch.isChecked = true
                    binding.tipsView.visibility = View.GONE
                }
            }
        } else {
            binding.tipsView.text = "通知监听服务未开启，无法监听打卡通知"
            binding.tipsView.setTextColor(Color.RED)
            binding.noticeSwitch.isChecked = false
            binding.tipsView.visibility = View.VISIBLE
        }
    }

    private fun turnOnNotificationMonitorService() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val componentName = ComponentName(context, NotificationMonitorService::class.java)

                // 检查当前组件状态
                val currentState = context.packageManager.getComponentEnabledSetting(componentName)
                if (currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    // 如果已经启用，先禁用
                    context.packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    delay(500) // 短暂延迟
                }

                // 重新启用
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(this, it)
        }
    }
}