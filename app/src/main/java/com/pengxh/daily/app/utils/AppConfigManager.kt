package com.pengxh.daily.app.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.io.File
import java.io.IOException

/**
 * 应用配置持久化管理器。
 *
 * 目标：把所有用户配置（打卡时间偏移、随机开关、超时秒数、重置时间等）
 * 保存在外部存储的固定文件 app_config.json 中，APK 升级后配置不丢失。
 *
 * 文件位置：Android/data/com.pengxh.daily.app/files/Documents/app_config.json
 *   （属于应用私有外部存储，卸载时会删除，但升级时不会覆盖）
 *
 * 版本迁移：
 *   - 每次新版本增加字段时，在 CURRENT_VERSION 递增，并在 migrate() 中补默认值。
 *   - 旧版本格式的文件会被自动迁移到新格式，无需用户手动处理。
 *
 * 使用方式：
 *   在 Application.onCreate() 末尾调用：AppConfigManager.init(context)
 *   在应用退出或重要配置变更后调用：AppConfigManager.save(context)
 */
object AppConfigManager {

    private const val kTag = "AppConfigManager"

    /** 当前配置文件版本号，每次增加字段时 +1 */
    private const val CURRENT_VERSION = 1

    /** 配置文件名（保存在应用私有外部 Documents 目录） */
    private const val CONFIG_FILE_NAME = "app_config.json"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // ─── 配置数据类 ────────────────────────────────────────────────────────
    data class AppConfig(
        /** 配置文件版本，用于迁移判断 */
        val version: Int = CURRENT_VERSION,

        // ── 随机时间偏移 ──────────────────────────────────────────────────
        /** 是否开启随机时间偏移 */
        val randomTimeEnabled: Boolean = true,
        /** 最多提前分钟数（如 5 表示最早可提前 5 分钟） */
        val randomBeforeMinutes: Int = 5,
        /** 最多推迟分钟数（如 10 表示最晚可推迟 10 分钟） */
        val randomAfterMinutes: Int = 10,

        // ── 任务执行参数 ──────────────────────────────────────────────────
        /** 打卡等待超时秒数（默认 30 秒）*/
        val checkinTimeoutSeconds: Int = Constant.DEFAULT_OVER_TIME,
        /** 任务每日重置小时（默认 0 点）*/
        val resetHour: Int = Constant.DEFAULT_RESET_HOUR,
        /** 打卡口令关键词（默认"打卡"）*/
        val taskKeyword: String = "打卡",
        /** 是否开启手势探测 */
        val gestureDetectorEnabled: Boolean = false,
        /** 完成后是否返回主屏幕 */
        val backToHomeEnabled: Boolean = false,
        /** 是否启用循环任务自动开始 */
        val autoTaskEnabled: Boolean = true
    )

    // ─── 公开 API ──────────────────────────────────────────────────────────

    /**
     * 初始化：在 Application.onCreate() 末尾调用。
     * 若配置文件存在则加载并应用到 SaveKeyValues；不存在则从 SaveKeyValues 读取当前值并写入文件。
     */
    fun init(context: Context) {
        try {
            val file = getConfigFile(context)
            if (file.exists()) {
                // 文件存在：加载 → 迁移 → 应用
                val json = file.readText(Charsets.UTF_8)
                val migrated = migrate(json)
                applyToPrefs(migrated)
                // 若迁移后版本有变化，立刻写回
                if (migrated.version < CURRENT_VERSION) {
                    writeFile(file, migrated.copy(version = CURRENT_VERSION))
                }
                Log.i(kTag, "配置已从文件加载：$file")
            } else {
                // 文件不存在：从 SharedPreferences 读取当前值，写入文件（首次初始化或全新安装）
                val config = readFromPrefs()
                writeFile(file, config)
                Log.i(kTag, "配置文件首次创建：$file")
            }
        } catch (e: Exception) {
            Log.e(kTag, "init 异常，使用默认配置: ${e.message}")
        }
    }

    /**
     * 保存当前 SharedPreferences 中的配置到文件。
     * 建议在以下时机调用：
     *   1. 用户在 TaskConfigActivity 修改配置后
     *   2. Activity.onPause / onDestroy
     */
    fun save(context: Context) {
        try {
            val file = getConfigFile(context)
            val config = readFromPrefs()
            writeFile(file, config)
            Log.i(kTag, "配置已保存到文件")
        } catch (e: Exception) {
            Log.e(kTag, "save 异常: ${e.message}")
        }
    }

    /**
     * 返回配置文件的绝对路径（供用户查看/备份）。
     */
    fun getConfigFilePath(context: Context): String {
        return getConfigFile(context).absolutePath
    }

    // ─── 私有实现 ──────────────────────────────────────────────────────────

    private fun getConfigFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir  // fallback 到内部存储
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CONFIG_FILE_NAME)
    }

    /**
     * 版本迁移：读取 JSON，补充新版本中新增的字段，返回最新格式的 AppConfig。
     *
     * 迁移规则：
     *   - 版本 1 → 2（示例）：新增 xxxField，默认值为 false
     *   - 旧文件中缺少的字段会被自动补上默认值，原有字段保持不变。
     */
    private fun migrate(json: String): AppConfig {
        return try {
            val obj: JsonObject = JsonParser.parseString(json).asJsonObject
            val fileVersion = obj.get("version")?.asInt ?: 0

            // 用 Gson 解析，缺失字段自动使用 data class 默认值
            val config = gson.fromJson(json, AppConfig::class.java)

            when {
                fileVersion < 1 -> {
                    // v0 → v1：首次引入版本字段，其余字段保持
                    config.copy(version = 1)
                }
                // 未来版本迁移在此追加：
                // fileVersion < 2 -> config.copy(version = 2, newField = defaultValue)
                else -> config
            }
        } catch (e: Exception) {
            Log.w(kTag, "迁移失败，使用默认配置: ${e.message}")
            AppConfig()
        }
    }

    /** 将 AppConfig 写入 SharedPreferences */
    private fun applyToPrefs(config: AppConfig) {
        SaveKeyValues.putValue(Constant.RANDOM_TIME_KEY, config.randomTimeEnabled)
        SaveKeyValues.putValue(Constant.RANDOM_BEFORE_MINUTES_KEY, config.randomBeforeMinutes)
        SaveKeyValues.putValue(Constant.RANDOM_AFTER_MINUTES_KEY, config.randomAfterMinutes)
        // 同时更新旧键，保持向后兼容
        SaveKeyValues.putValue(Constant.RANDOM_MINUTE_RANGE_KEY, config.randomBeforeMinutes)
        SaveKeyValues.putValue(Constant.STAY_DD_TIMEOUT_KEY, config.checkinTimeoutSeconds)
        SaveKeyValues.putValue(Constant.RESET_TIME_KEY, config.resetHour)
        SaveKeyValues.putValue(Constant.TASK_NAME_KEY, config.taskKeyword)
        SaveKeyValues.putValue(Constant.GESTURE_DETECTOR_KEY, config.gestureDetectorEnabled)
        SaveKeyValues.putValue(Constant.BACK_TO_HOME_KEY, config.backToHomeEnabled)
        SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, config.autoTaskEnabled)
    }

    /** 从 SharedPreferences 读取当前配置构建 AppConfig */
    private fun readFromPrefs(): AppConfig {
        val legacyRange = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
        return AppConfig(
            version = CURRENT_VERSION,
            randomTimeEnabled = SaveKeyValues.getValue(Constant.RANDOM_TIME_KEY, true) as Boolean,
            randomBeforeMinutes = SaveKeyValues.getValue(
                Constant.RANDOM_BEFORE_MINUTES_KEY, legacyRange
            ) as Int,
            randomAfterMinutes = SaveKeyValues.getValue(
                Constant.RANDOM_AFTER_MINUTES_KEY, legacyRange * 2
            ) as Int,
            checkinTimeoutSeconds = SaveKeyValues.getValue(
                Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
            ) as Int,
            resetHour = SaveKeyValues.getValue(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            ) as Int,
            taskKeyword = SaveKeyValues.getValue(Constant.TASK_NAME_KEY, "打卡") as String,
            gestureDetectorEnabled = SaveKeyValues.getValue(
                Constant.GESTURE_DETECTOR_KEY, false
            ) as Boolean,
            backToHomeEnabled = SaveKeyValues.getValue(
                Constant.BACK_TO_HOME_KEY, false
            ) as Boolean,
            autoTaskEnabled = SaveKeyValues.getValue(
                Constant.TASK_AUTO_START_KEY, true
            ) as Boolean
        )
    }

    private fun writeFile(file: File, config: AppConfig) {
        try {
            file.writeText(gson.toJson(config), Charsets.UTF_8)
        } catch (e: IOException) {
            Log.e(kTag, "写入配置文件失败: ${e.message}")
        }
    }
}
