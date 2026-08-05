package com.pengxh.daily.app.widgets

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.DialogCommandHelpBinding
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 远程快捷指令说明弹窗：列出通过微信/QQ 等聊天工具可发送给本机的指令，
 * 方便用户在其他机器上知道该输入什么内容。
 */
class CommandHelpDialog private constructor(builder: Builder) : Dialog(builder.context) {

    class Builder {
        lateinit var context: Context

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun build(): CommandHelpDialog {
            return CommandHelpDialog(this)
        }
    }

    private val binding: DialogCommandHelpBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.initDialogLayoutParams(0.85f)
        setCanceledOnTouchOutside(false)
        binding.commandContent.text = buildCommandHelp()
        binding.confirmButton.setOnClickListener { dismiss() }
    }

    /** 生成指令说明文本：指令名加粗+主题色，描述为普通文字 */
    private fun buildCommandHelp(): CharSequence {
        val key = SaveKeyValues.getValue(Constant.TASK_NAME_KEY, "打卡") as String
        val commands = listOf(
            "请假 明天" to "设置请假（可写 明天 / 8月12日 / 8月12日到8月14日）",
            "销假 下周三" to "取消请假（销假 全部 = 清空所有）",
            "电量" to "查询手机剩余电量",
            "启动" to "开始执行今日任务",
            "停止" to "停止执行今日任务",
            "开始循环" to "开启每日自动循环",
            "暂停循环" to "暂停每日自动循环",
            "息屏" to "开启伪息屏",
            "亮屏" to "关闭伪息屏",
            "考勤记录" to "查询当天考勤明细",
            "状态" to "回报任务链运行状态",
            "打卡" to "手动打卡（口令：$key，可在任务配置中修改）"
        )
        val builder = SpannableStringBuilder()
        val accentColor = context.getColor(R.color.theme_color)
        builder.append("在微信/QQ/企业微信等聊天中，给本机发送以下指令即可远程控制：\n\n")
        commands.forEachIndexed { index, (name, desc) ->
            val start = builder.length
            builder.append("【$name】")
            builder.setSpan(
                ForegroundColorSpan(accentColor),
                start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.append(desc)
            if (index != commands.lastIndex) {
                builder.append("\n")
            }
        }
        builder.append("\n\n（指令仅在通知监听服务开启时生效）")
        return builder
    }
}
