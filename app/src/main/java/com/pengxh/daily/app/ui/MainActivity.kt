package com.pengxh.daily.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.ScaleAnimation
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.SkipDates
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.daily.app.utils.UpdateManager
import com.pengxh.daily.app.widgets.CalendarMultiSelectDialog
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemOffsets
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.setScreenBrightness
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs

/**
 * 主界面（纯 UI 层）。
 *
 * 任务链调度、超时重试、进度推进全部在 CountDownTimerService（任务引擎）中，
 * 本页面只负责：
 *   - 展示任务列表与引擎状态（TASK_STATE_CHANGED 广播驱动）；
 *   - 通过 START/STOP_DAILY_TASK 广播下发启动/停止指令；
 *   - 伪灭屏蒙层、手势、悬浮窗等界面功能。
 */
class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val kTag = "MainActivity"
    private val context = this

    private val actions by lazy {
        listOf(
            MessageType.SHOW_MASK_VIEW.action,
            MessageType.HIDE_MASK_VIEW.action,
            MessageType.UPDATE_RESET_TICK_TIME.action,
            MessageType.TASK_STATE_CHANGED.action,
            MessageType.BACK_TO_MAIN_ONLY.action
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var gestureDetector: GestureDetector
    private lateinit var dailyTaskAdapter: DailyTaskAdapter
    private var taskBeans = mutableListOf<DailyTaskBean>()
    private val marginOffset by lazy { 16.dp2px(this) }
    private var isTaskStarted = false
    private var isRefresh = false
    private val gson by lazy { Gson() }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                when (MessageType.fromAction(it)) {
                    MessageType.SHOW_MASK_VIEW -> {
                        if (!binding.maskView.isVisible) {
                            showMaskView()
                        }
                    }

                    MessageType.HIDE_MASK_VIEW -> {
                        if (binding.maskView.isVisible) {
                            hideMaskView()
                        }
                    }

                    MessageType.UPDATE_RESET_TICK_TIME -> {
                        binding.repeatTimeView.text = intent.getStringExtra("message")
                    }

                    MessageType.TASK_STATE_CHANGED -> {
                        renderEngineState(intent)
                    }

                    MessageType.BACK_TO_MAIN_ONLY -> {
                        // 超时/请假等场景引擎退出目标应用时的通知，仅记日志
                        LogFileManager.writeLog("回到主界面（引擎通知）")
                    }

                    else -> {}
                }
            }
        }
    }

    /** 渲染任务引擎广播来的状态快照 */
    private fun renderEngineState(intent: Intent) {
        val stateName = intent.getStringExtra("state") ?: return
        val taskIndex = intent.getIntExtra("taskIndex", -1)
        val actualTime = intent.getStringExtra("actualTime") ?: "--:--:--"
        val message = intent.getStringExtra("message") ?: ""
        isTaskStarted = intent.getBooleanExtra("active", false)
        updateExecuteButton()

        when (stateName) {
            "DONE", "SKIPPED" -> {
                if (message.isNotBlank()) {
                    binding.nextTaskDescView.text = message
                    binding.nextTaskDescView.setTextColor(R.color.ios_green.convertColor(context))
                }
                dailyTaskAdapter.updateCurrentTaskState(-1)
                refreshRingProgress()
            }

            "FAILED" -> {
                if (message.isNotBlank()) {
                    binding.nextTaskDescView.text = message
                    binding.nextTaskDescView.setTextColor(R.color.red.convertColor(context))
                }
                dailyTaskAdapter.updateCurrentTaskState(-1)
                refreshRingProgress()
            }

            "COUNTING" -> {
                binding.nextTaskDescView.setTextColor(R.color.theme_color.convertColor(context))
                if (message.isNotBlank()) {
                    binding.nextTaskDescView.text = message
                }
                if (taskIndex >= 0) {
                    dailyTaskAdapter.updateCurrentTaskState(taskIndex, actualTime)
                }
            }

            else -> {
                if (message.isNotBlank()) {
                    binding.nextTaskDescView.text = message
                    binding.nextTaskDescView.setTextColor(R.color.theme_color.convertColor(context))
                }
                dailyTaskAdapter.updateCurrentTaskState(-1)
            }
        }
    }

    private fun updateExecuteButton() {
        if (isTaskStarted) {
            binding.executeTaskButton.setBackgroundResource(R.drawable.bg_pill_stop)
            binding.executeTaskButton.text = "停止"
            binding.statusCapsuleView.text = "引擎运行中"
            binding.statusCapsuleView.setTextColor(R.color.accent_green.convertColor(context))
        } else {
            binding.executeTaskButton.setBackgroundResource(R.drawable.bg_pill_primary)
            binding.executeTaskButton.text = "启动"
            binding.statusCapsuleView.text = "引擎待机"
            binding.statusCapsuleView.setTextColor(R.color.text_primary.convertColor(context))
        }
    }

    /** 计算下一个待执行的最近任务时间，并刷新概览卡 */
    private fun refreshNextTask() {
        val now = timeFormat.format(Date())
        val next = taskBeans.map { it.time }.sorted().firstOrNull { it >= now }
            ?: taskBeans.firstOrNull()?.time
        binding.nextTaskTimeView.text = next ?: "--:--"
        binding.nextTaskDescView.setTextColor(R.color.theme_color.convertColor(context))
        binding.nextTaskDescView.text = when {
            taskBeans.isEmpty() -> "暂无任务"
            next != null -> "等待执行"
            else -> "今日任务已完成"
        }
    }

    /** 依据当日打卡日志统计今日完成数，刷新进度环 */
    private fun refreshRingProgress() {
        val done = try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val month = SimpleDateFormat("yyyyMM", Locale.CHINA).format(Date())
            val file = File(dir, "checkin_log_$month.txt")
            if (dir == null || !file.exists()) {
                0
            } else {
                val prefix = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                file.useLines { lines ->
                    lines.count { it.startsWith("[$prefix") && it.contains("打卡成功") }
                }
            }
        } catch (e: Exception) {
            0
        }
        binding.ringProgressView.setProgress(done, taskBeans.size)
    }

    /** 从引擎持久化状态同步启动/停止按钮（页面重建时恢复 UI） */
    private fun syncTaskStartedFromPrefs() {
        val active = SaveKeyValues.getValue(Constant.ENGINE_ACTIVE_KEY, false) as Boolean
        val date = SaveKeyValues.getValue(Constant.ENGINE_DATE_KEY, "") as String
        isTaskStarted = active && date == TimeKit.getTodayDate()
        updateExecuteButton()
    }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        insetsController = WindowCompat.getInsetsController(window, binding.rootView)

        // 顶栏时钟：秒级刷新当前时间与日期
        mainHandler.post(object : Runnable {
            override fun run() {
                val now = Date()
                binding.headerTimeView.text = timeFormat.format(now)
                binding.headerDateView.text = dateFormat.format(now)
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        BroadcastManager.getDefault().registerReceivers(this, actions, broadcastReceiver)

        // 显示悬浮窗
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        } else {
            // 悬浮窗权限并显示悬浮窗
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        Intent(this, ForegroundRunningService::class.java).apply {
            startForegroundService(this)
        }

        Intent(this, CountDownTimerService::class.java).apply {
            // 任务引擎以前台服务方式独立运行，Activity 销毁不影响任务链
            startForegroundService(this)
        }

        // Android 13+ 需要运行时申请通知权限，否则前台服务通知、悬浮窗倒计时提示都不显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 每天首次打开应用时静默检查一次更新
        UpdateManager.autoCheckOncePerDay(this)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean) {
                    val deltaY = abs(e2.y - (e1?.y ?: e2.y))

                    // 从上向下滑动手势
                    if (deltaY > 1000
                        && (e2.y - (e1?.y ?: e2.y)) > 0
                        && !binding.maskView.isVisible
                    ) {
                        showMaskView()
                        return true
                    }

                    // 从下向上滑动手势
                    if (deltaY > 1000
                        && (e2.y - (e1?.y ?: e2.y)) < 0
                        && binding.maskView.isVisible
                    ) {
                        hideMaskView()
                        return true
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        })

        // 数据
        taskBeans = DatabaseWrapper.loadAllTask()
        if (taskBeans.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
        }
        dailyTaskAdapter = DailyTaskAdapter(this, taskBeans)
        dailyTaskAdapter.setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
            override fun onItemClick(position: Int) {
                itemClick(position)
            }

            override fun onItemLongClick(position: Int) {
                itemLongClick(position)
            }
        })
        binding.recyclerView.adapter = dailyTaskAdapter
        binding.recyclerView.addItemDecoration(
            RecyclerViewItemOffsets(
                marginOffset,
                marginOffset shr 1,
                marginOffset,
                marginOffset shr 1
            )
        )
        refreshNextTask()
        refreshRingProgress()

        if (SaveKeyValues.getValue("isFirst", true) as Boolean) {
            AlertMessageDialog.Builder()
                .setContext(this)
                .setTitle("温馨提醒")
                .setMessage("本软件仅供内部使用，严禁商用或者用作其他非法用途")
                .setPositiveButton("知道了")
                .setOnDialogButtonClickListener(object :
                    AlertMessageDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick() {
                        SaveKeyValues.putValue("isFirst", false)
                    }
                }).build().show()
        }

        // 打卡成功后回到主界面时自动恢复假息屏
        if (intent?.getBooleanExtra("autoMask", false) == true) {
            // 延迟 300ms 等待界面完全渲染后再显示蒙层
            mainHandler.postDelayed({ showMaskView() }, 300)
        }

        // 恢复启动/停止按钮状态，并向引擎查询一次最新状态（异步回报后刷新）
        syncTaskStartedFromPrefs()
        BroadcastManager.getDefault().sendBroadcast(this, MessageType.QUERY_TASK_STATE.action)
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                Intent(this, FloatingWindowService::class.java).apply {
                    startService(this)
                }
            }
        }

    // Android 13+ 通知权限申请结果（无论用户是否允许都不阻塞主流程）
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * 列表项单击
     * */
    private fun itemClick(adapterPosition: Int) {
        if (isTaskStarted) {
            "任务进行中，无法修改".show(this)
            return
        }
        val item = taskBeans[adapterPosition]
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        val titleView = view.findViewById<MaterialTextView>(R.id.titleView)
        titleView.text = "修改任务时间"
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        timePicker.setDefaultValue(item.convertToTimeEntity())
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                timePicker.selectedHour,
                timePicker.selectedMinute,
                timePicker.selectedSecond
            )
            item.time = time
            DatabaseWrapper.updateTask(item)
            taskBeans = DatabaseWrapper.loadAllTask()
            dailyTaskAdapter.refresh(taskBeans)
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * 列表项长按
     * */
    private fun itemLongClick(adapterPosition: Int) {
        if (isTaskStarted) {
            "任务进行中，无法删除".show(this)
            return
        }
        AlertControlDialog.Builder()
            .setContext(this)
            .setTitle("删除提示")
            .setMessage("确定要删除这个任务吗")
            .setNegativeButton("取消")
            .setPositiveButton("确定")
            .setOnDialogButtonClickListener(object :
                AlertControlDialog.OnDialogButtonClickListener {
                override fun onConfirmClick() {
                    try {
                        val item = taskBeans[adapterPosition]
                        DatabaseWrapper.deleteTask(item)
                        taskBeans.removeAt(adapterPosition)
                        dailyTaskAdapter.refresh(taskBeans)
                        if (taskBeans.isEmpty()) {
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyView.visibility = View.VISIBLE
                        } else {
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyView.visibility = View.GONE
                        }
                    } catch (e: IndexOutOfBoundsException) {
                        e.printStackTrace()
                        "删除失败，请刷新重试".show(context)
                    }
                }

                override fun onCancelClick() {

                }
            }).build().show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureDetector.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun initEvent() {
        binding.executeTaskButton.setOnClickListener {
            if (isTaskStarted) {
                // 引擎收到后会停止任务链并广播最新状态
                BroadcastManager.getDefault().sendBroadcast(
                    this, MessageType.STOP_DAILY_TASK.action
                )
            } else {
                if (DatabaseWrapper.loadAllTask().isEmpty()) {
                    "循环任务启动失败，请先添加任务时间点".show(this)
                    return@setOnClickListener
                }
                // 引擎收到后会启动任务链并广播最新状态
                BroadcastManager.getDefault().sendBroadcast(
                    this, MessageType.START_DAILY_TASK.action
                )
            }
        }

        binding.addTaskButton.setOnClickListener {
            if (isTaskStarted) {
                "任务进行中，无法添加".show(this)
                return@setOnClickListener
            }

            if (taskBeans.isNotEmpty()) {
                createTask()
            } else {
                BottomActionSheet.Builder()
                    .setContext(this)
                    .setActionItemTitle(arrayListOf("添加任务", "导入任务"))
                    .setItemTextColor(R.color.theme_color.convertColor(this))
                    .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                        override fun onActionItemClick(position: Int) {
                            when (position) {
                                0 -> createTask()
                                1 -> importTask()
                            }
                        }
                    }).build().show()
            }
        }

        binding.settingsButton.setOnClickListener {
            navigatePageTo<SettingsActivity>()
        }

        binding.quickLeaveLayout.setOnClickListener {
            showSkipDatesDialog()
        }

        binding.quickNoticeLayout.setOnClickListener {
            navigatePageTo<NoticeRecordActivity>()
        }

        binding.quickStatsLayout.setOnClickListener {
            showMonthStats()
        }

        binding.refreshView.setOnRefreshListener {
            isRefresh = true
            lifecycleScope.launch(Dispatchers.Main) {
                val result = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                delay(500)
                binding.refreshView.finishRefresh()
                isRefresh = false
                taskBeans = result
                refreshNextTask()
                refreshRingProgress()
                dailyTaskAdapter.refresh(result, itemComparator)
            }
        }
        binding.refreshView.setEnableLoadMore(false)
    }

    private val itemComparator = object : NormalRecyclerAdapter.ItemComparator<DailyTaskBean> {
        override fun areItemsTheSame(oldItem: DailyTaskBean, newItem: DailyTaskBean): Boolean {
            return oldItem.id == newItem.id && oldItem.time == newItem.time
        }

        override fun areContentsTheSame(oldItem: DailyTaskBean, newItem: DailyTaskBean): Boolean {
            return oldItem.time == newItem.time
        }
    }

    override fun observeRequestState() {

    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (binding.maskView.isVisible) {
                hideMaskView()
            } else {
                showMaskView()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var clockAnimationRunnable = object : Runnable {
        override fun run() {
            // 确保视图已经布局完成
            if (binding.maskView.width == 0 || binding.maskView.height == 0) return

            // 获取时钟控件尺寸
            binding.clockView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val clockWidth = binding.clockView.measuredWidth
            val clockHeight = binding.clockView.measuredHeight

            // 计算可移动范围
            val maxX = binding.maskView.width - clockWidth
            val maxY = binding.maskView.height - clockHeight

            // 确保范围有效
            if (maxX <= 0 || maxY <= 0) return

            // 生成随机位置
            val random = Random()
            val newX = random.nextInt(maxX.coerceAtLeast(1))
            val newY = random.nextInt(maxY.coerceAtLeast(1))

            // 应用动画移动到新位置
            binding.clockView.animate()
                .x(newX.toFloat())
                .y(newY.toFloat())
                .setDuration(1000)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            // 每30秒执行一次位置变换
            mainHandler.postDelayed(this, 30000)
        }
    }

    /**
     * 显示蒙层以及其它组件
     * */
    private fun showMaskView() {
        //隐藏悬浮窗显示
        BroadcastManager.getDefault().sendBroadcast(this, MessageType.HIDE_FLOATING_WINDOW.action)

        //隐藏状态栏和导航栏显示
        insetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        //显示蒙层
        binding.maskView.visibility = View.VISIBLE
        val visibleAction = ScaleAnimation(1.0f, 1.0f, 0.0f, 1.0f)
        visibleAction.duration = 500
        binding.maskView.startAnimation(visibleAction)
        window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF)

        //隐藏任务界面
        binding.rootView.visibility = View.GONE

        //启动时钟位置变换动画
        mainHandler.postDelayed(clockAnimationRunnable, 30000)
    }

    /**
     * 隐藏蒙层以及其它组件
     * */
    private fun hideMaskView() {
        //恢复悬浮窗显示
        BroadcastManager.getDefault().sendBroadcast(this, MessageType.SHOW_FLOATING_WINDOW.action)

        //停止时钟动画
        mainHandler.removeCallbacks(clockAnimationRunnable)

        //恢复状态栏和导航栏显示
        insetsController.apply {
            show(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        //隐藏蒙层
        binding.maskView.visibility = View.GONE
        val invisibleAction = ScaleAnimation(1.0f, 1.0f, 1.0f, 0.0f)
        invisibleAction.duration = 500
        binding.maskView.startAnimation(invisibleAction)
        window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        //显示任务界面
        binding.rootView.visibility = View.VISIBLE
    }

    private fun createTask() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        val titleView = view.findViewById<MaterialTextView>(R.id.titleView)
        titleView.text = "添加任务"
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                timePicker.selectedHour,
                timePicker.selectedMinute,
                timePicker.selectedSecond
            )

            if (DatabaseWrapper.isTaskTimeExist(time)) {
                "任务时间点已存在".show(this)
                return@setOnClickListener
            }
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
            val bean = DailyTaskBean().apply {
                this.time = time
            }
            DatabaseWrapper.insert(bean)
            taskBeans = DatabaseWrapper.loadAllTask()
            dailyTaskAdapter.refresh(taskBeans)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun importTask() {
        AlertInputDialog.Builder()
            .setContext(this)
            .setTitle("导入任务")
            .setHintMessage("请将导出的任务粘贴到这里")
            .setNegativeButton("取消")
            .setPositiveButton("确定")
            .setOnDialogButtonClickListener(object :
                AlertInputDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(value: String) {
                    val type = object : TypeToken<List<DailyTaskBean>>() {}.type
                    try {
                        val tasks = gson.fromJson<List<DailyTaskBean>>(value, type)
                        var imported = 0
                        var skipped = 0
                        for (task in tasks) {
                            // 校验时间格式，非法数据直接跳过，防止后续执行时解析崩溃
                            if (!task.time.matches(Regex("\\d{2}:\\d{2}:\\d{2}"))) {
                                skipped++
                                continue
                            }
                            if (DatabaseWrapper.isTaskTimeExist(task.time)) {
                                skipped++
                                continue
                            }
                            DatabaseWrapper.insert(task)
                            imported++
                        }
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.emptyView.visibility = View.GONE
                        taskBeans = DatabaseWrapper.loadAllTask()
                        dailyTaskAdapter.refresh(taskBeans)
                        if (skipped > 0) {
                            "任务导入完成：成功${imported}条，跳过${skipped}条".show(context)
                        } else {
                            "任务导入成功".show(context)
                        }
                    } catch (e: JsonSyntaxException) {
                        e.printStackTrace()
                        "导入失败，请确认导入的是正确的任务数据".show(context)
                    }
                }

                override fun onCancelClick() {}
            }).build().show()
    }

    /** 快捷操作：请假日历多选 */
    private fun showSkipDatesDialog() {
        CalendarMultiSelectDialog.Builder()
            .setContext(this)
            .setInitialDates(SkipDates.getAll())
            .setOnDialogButtonClickListener(object :
                CalendarMultiSelectDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(selectedDates: List<String>) {
                    val old = SkipDates.getAll()
                    val newSet = selectedDates.toSet()
                    val added = newSet - old
                    val removed = old - newSet
                    removed.forEach { SkipDates.remove(it) }
                    added.forEach { SkipDates.add(it) }
                    BroadcastManager.getDefault().sendBroadcast(
                        this@MainActivity, MessageType.SKIP_DATES_CHANGED.action
                    )
                    when {
                        added.isEmpty() && removed.isEmpty() ->
                            "请假日期未变化".show(context)

                        added.isNotEmpty() && removed.isNotEmpty() ->
                            "已添加 ${added.size} 天，取消 ${removed.size} 天".show(context)

                        added.isNotEmpty() ->
                            "已添加 ${added.size} 天请假日期".show(context)

                        else ->
                            "已取消 ${removed.size} 天请假日期".show(context)
                    }
                }
            }).build().show()
    }

    /** 快捷操作：本月打卡统计 */
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(kTag, "onNewIntent: ${packageName}回到前台")
        if (!binding.maskView.isVisible) {
            showMaskView()
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时同步一次引擎状态（服务重启/跨天/远程指令变化后 UI 可能已过期）
        syncTaskStartedFromPrefs()
        BroadcastManager.getDefault().sendBroadcast(this, MessageType.QUERY_TASK_STATE.action)
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(this, it)
        }
        // 移除所有 Handler 回调（顶部时钟刷新、蒙层时钟动画），防止 Activity 泄漏
        mainHandler.removeCallbacksAndMessages(null)
    }
}
