package com.pengxh.daily.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityLogViewerBinding
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志 / 打卡日志查看页。
 *
 * 日志文件位于应用私有外部 Documents 目录（与 LogFileManager 写入位置一致）：
 *   - 运行日志：app_runtime_log.txt（可能已轮转为 app_runtime_log_时间戳.txt）
 *   - 打卡日志：checkin_log_yyyyMM.txt（按月一个文件）
 *
 * 支持分享：复制到缓存目录后通过 FileProvider 授予临时读取权限发送。
 */
class LogViewerActivity : KotlinBaseActivity<ActivityLogViewerBinding>() {

    private val context = this

    /** 当前是否正在查看打卡日志（false = 运行日志） */
    private var showingCheckinLog = false

    override fun initViewBinding(): ActivityLogViewerBinding {
        return ActivityLogViewerBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.menu_share_log) {
                shareCurrentLog()
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        showRuntimeLog()
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.runtimeLogButton.setOnClickListener {
            showRuntimeLog()
        }
        binding.checkinLogButton.setOnClickListener {
            showCheckinLog()
        }
    }

    private fun showRuntimeLog() {
        showingCheckinLog = false
        binding.toolbar.title = "运行日志"
        binding.logView.text = readTail(currentRuntimeLogFile())
        updateLogButtonStyles()
    }

    private fun showCheckinLog() {
        showingCheckinLog = true
        binding.toolbar.title = "打卡日志（本月）"
        binding.logView.text = readTail(currentCheckinLogFile())
        updateLogButtonStyles()
    }

    /** 当前选中的日志页按钮高亮主题色，另一个置为玻璃底色 */
    private fun updateLogButtonStyles() {
        applyLogButtonStyle(binding.runtimeLogButton, !showingCheckinLog)
        applyLogButtonStyle(binding.checkinLogButton, showingCheckinLog)
    }

    private fun applyLogButtonStyle(button: MaterialButton, active: Boolean) {
        val bg = if (active) {
            R.color.theme_color.convertColor(this)
        } else {
            R.color.surface_glass_top.convertColor(this)
        }
        button.backgroundTintList = ColorStateList.valueOf(bg)
        button.setTextColor(
            if (active) Color.WHITE else R.color.text_primary.convertColor(this)
        )
    }

    private fun logDir(): File? {
        return getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
    }

    /** 当前运行日志文件（若已轮转，取最新一个） */
    private fun currentRuntimeLogFile(): File? {
        val dir = logDir() ?: return null
        val current = File(dir, "app_runtime_log.txt")
        if (current.exists() && current.length() > 0) {
            return current
        }
        // 找最新的轮转文件
        return dir.listFiles { file ->
            file.name.startsWith("app_runtime_log") && file.name.endsWith(".txt")
        }?.maxByOrNull { it.lastModified() }
    }

    private fun currentCheckinLogFile(): File? {
        val dir = logDir() ?: return null
        val month = SimpleDateFormat("yyyyMM", Locale.CHINA).format(Date())
        return File(dir, "checkin_log_$month.txt")
    }

    /** 读取文件末尾内容（最多 300 行，防止大文件卡顿） */
    private fun readTail(file: File?, maxLines: Int = 300): String {
        if (file == null || !file.exists() || file.length() == 0L) {
            return "暂无日志"
        }
        return try {
            val lines = file.readLines()
            if (lines.size > maxLines) {
                "……（仅显示最近 $maxLines 行）\n\n" + lines.takeLast(maxLines).joinToString("\n")
            } else {
                lines.joinToString("\n")
            }
        } catch (e: Exception) {
            "日志读取失败：${e.message}"
        }
    }

    /** 分享当前查看的日志文件 */
    private fun shareCurrentLog() {
        val src = if (showingCheckinLog) currentCheckinLogFile() else currentRuntimeLogFile()
        if (src == null || !src.exists() || src.length() == 0L) {
            "暂无可分享的日志".show(context)
            return
        }
        try {
            val shareDir = File(cacheDir, "logs")
            if (!shareDir.exists()) {
                shareDir.mkdirs()
            }
            val dest = File(shareDir, src.name)
            src.copyTo(dest, overwrite = true)

            val uri = FileProvider.getUriForFile(
                context, "${packageName}.fileprovider", dest
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享日志"))
        } catch (e: Exception) {
            "分享失败：${e.message}".show(context)
        }
    }
}
