package com.pengxh.daily.app.utils

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.pengxh.daily.app.BuildConfig
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内检查更新与下载安装。
 *
 * 版本信息文件 version.json 位于仓库根目录，从多个镜像拉取（按顺序尝试）：
 *   - jsdelivr CDN（国内访问快，优先）
 *   - gh-proxy.com 代理
 *   - GitHub raw 直连（兜底）
 *
 * version.json 结构：
 * {
 *   "versionCode": 2252,
 *   "versionName": "2.2.6.0",
 *   "changelog": "更新说明",
 *   "mirrors": ["APK 直链1", "APK 直链2", ...]
 * }
 *
 * 下载使用系统 DownloadManager 后台进行，完成后自动调用系统安装器。
 */
object UpdateManager {

    private const val UPDATE_LAST_CHECK_DATE_KEY = "UPDATE_LAST_CHECK_DATE_KEY"
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 6000

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val mirrors: List<String>
    )

    private val versionJsonMirrors = listOf(
        "https://cdn.jsdelivr.net/gh/HUAIDAO1104/DailyTask-26.02.27@master/version.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/HUAIDAO1104/DailyTask-26.02.27/master/version.json",
        "https://raw.githubusercontent.com/HUAIDAO1104/DailyTask-26.02.27/master/version.json"
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var checking = false

    @Volatile
    private var apkMirrors: List<String> = emptyList()

    @Volatile
    private var apkMirrorIndex = 0

    @Volatile
    private var versionName = ""

    @Volatile
    private var downloadId = -1L

    @Volatile
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            unregisterDownloadReceiver(context)
            val status = queryDownloadStatus(context, id)
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                installApk(context)
            } else if (apkMirrorIndex + 1 < apkMirrors.size) {
                // 当前镜像下载失败，切换下一个镜像重试
                apkMirrorIndex++
                enqueueDownload(context)
            } else {
                Toast.makeText(context, "更新包下载失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 每天最多自动检查一次；返回 false 表示今日已检查或已在检查中 */
    fun autoCheckOncePerDay(activity: Activity): Boolean {
        if (checking) return false
        val today = TimeKit.getTodayDate()
        val last = SaveKeyValues.getValue(UPDATE_LAST_CHECK_DATE_KEY, "") as String
        if (last == today) return false
        SaveKeyValues.putValue(UPDATE_LAST_CHECK_DATE_KEY, today)
        check(activity, manual = false)
        return true
    }

    /** 检查更新；manual=true 时无更新或失败给出提示 */
    fun check(activity: Activity, manual: Boolean) {
        if (checking) {
            if (manual) "正在检查更新，请稍候…".show(activity)
            return
        }
        if (manual) "正在检查更新…".show(activity)
        checking = true
        Thread {
            try {
                val info = fetchUpdateInfo()
                mainHandler.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (info == null) {
                        if (manual) "检查更新失败，请检查网络后重试".show(activity)
                    } else if (info.versionCode > BuildConfig.VERSION_CODE) {
                        showUpdateDialog(activity, info)
                    } else if (manual) {
                        "当前已是最新版本".show(activity)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (manual && !activity.isFinishing && !activity.isDestroyed) {
                        "检查更新失败，请稍后重试".show(activity)
                    }
                }
            } finally {
                checking = false
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.versionName}")
            .setMessage(buildString {
                append("当前版本：v${BuildConfig.VERSION_NAME}\n")
                append("最新版本：v${info.versionName}\n\n")
                append("更新内容：\n")
                append(info.changelog.ifBlank { "本次更新优化了使用体验，建议尽快升级。" })
            })
            .setPositiveButton("下载并安装") { _, _ ->
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    requestUnknownSourcePermission(activity)
                } else {
                    startDownload(activity, info)
                }
            }
            .setNegativeButton("暂不更新", null)
            .show()
    }

    private fun requestUnknownSourcePermission(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("需要安装权限")
            .setMessage("安装更新包需要允许安装未知来源应用。请前往系统设置开启后，再重新点击「下载并安装」。")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    )
                } catch (e: Exception) {
                    "无法打开设置页，请手动允许安装未知应用".show(activity)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestUnknownSourceFromContext(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(context, "请手动允许安装未知来源应用后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDownload(activity: Activity, info: UpdateInfo) {
        apkMirrors = info.mirrors
        apkMirrorIndex = 0
        versionName = info.versionName
        enqueueDownload(activity.applicationContext)
    }

    private fun enqueueDownload(context: Context) {
        if (apkMirrors.isEmpty() || apkMirrorIndex >= apkMirrors.size) {
            Toast.makeText(context, "更新包下载失败，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val url = apkMirrors[apkMirrorIndex]
        val fileName = "DailyTask_${versionName}.apk"
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("每日打卡更新")
            setDescription("正在下载 v$versionName，请保持网络畅通")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        try {
            registerDownloadReceiver(context)
            downloadId = dm.enqueue(request)
        } catch (e: Exception) {
            unregisterDownloadReceiver(context)
            "无法开始下载，请稍后重试".show(context)
        }
    }

    private fun queryDownloadStatus(context: Context, id: Long): Int {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            var status = DownloadManager.STATUS_FAILED
            cursor?.use {
                if (it.moveToFirst()) {
                    status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
            }
            status
        } catch (e: Exception) {
            DownloadManager.STATUS_FAILED
        }
    }

    private fun installApk(context: Context) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir == null) {
            Toast.makeText(context, "无法访问存储目录，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(dir, "DailyTask_${versionName}.apk")
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "更新包文件异常，请重新下载", Toast.LENGTH_SHORT).show()
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            requestUnknownSourceFromContext(context)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开安装界面，请手动安装更新包", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDownloadReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                context, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(downloadReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterDownloadReceiver(context: Context) {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // 可能已自动注销，忽略
        }
        receiverRegistered = false
    }

    /** 拉取全部镜像的 version.json，取 versionCode 最大的结果，防止单个镜像缓存过期导致误报 */
    private fun fetchUpdateInfo(): UpdateInfo? {
        var newest: UpdateInfo? = null
        for (url in versionJsonMirrors) {
            try {
                val json = httpGetString(url) ?: continue
                val obj = JSONObject(json)
                val versionCode = obj.optInt("versionCode", -1)
                if (versionCode <= 0) continue
                val versionName = obj.optString("versionName", "")
                val changelog = obj.optString("changelog", "")
                val mirrors = obj.optJSONArray("mirrors")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { it.isNotBlank() }
                    }
                } ?: emptyList()
                if (mirrors.isEmpty()) continue
                if (newest == null || versionCode > newest.versionCode) {
                    newest = UpdateInfo(versionCode, versionName, changelog, mirrors)
                }
            } catch (e: Exception) {
                // 该镜像不可用，跳过继续下一个
            }
        }
        return newest
    }

    private fun httpGetString(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "DailyTask/Android")
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
