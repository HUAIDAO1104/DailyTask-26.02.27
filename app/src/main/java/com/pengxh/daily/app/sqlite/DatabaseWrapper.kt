package com.pengxh.daily.app.sqlite

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.EmailConfigBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DatabaseWrapper {
    private val dailyTaskDao by lazy { DailyTaskApplication.get().dataBase.dailyTaskDao() }

    fun loadAllTask(): ArrayList<DailyTaskBean> {
        return dailyTaskDao.loadAll() as ArrayList<DailyTaskBean>
    }

    fun isTaskTimeExist(time: String): Boolean {
        return dailyTaskDao.queryTaskByTime(time) > 0
    }

    fun updateTask(bean: DailyTaskBean) {
        dailyTaskDao.update(bean)
    }

    fun deleteTask(bean: DailyTaskBean) {
        dailyTaskDao.delete(bean)
    }

    fun insert(bean: DailyTaskBean) {
        dailyTaskDao.insert(bean)
    }

    /*****************************************************************************************/
    private val noticeDao by lazy { DailyTaskApplication.get().dataBase.noticeDao() }

    fun deleteAllNotice() {
        noticeDao.deleteAll()
    }

    fun loadNoticeByTime(pageSize: Int, offset: Int): MutableList<NotificationBean> {
        return noticeDao.loadNoticeByTime(pageSize, offset)
    }

    fun loadCurrentDayNotice(): MutableList<NotificationBean> {
        return noticeDao.loadCurrentDayNotice(TimeKit.getTodayDate())
    }

    fun insertNotice(bean: NotificationBean) {
        noticeDao.insert(bean)
        // 自动清理 30 天前的通知记录，防止表无限增长导致分页查询变慢
        try {
            val deadline = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(
                Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            )
            noticeDao.deleteOlderThan(deadline)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /*****************************************************************************************/
    private val emailConfigDao by lazy { DailyTaskApplication.get().dataBase.emailConfigDao() }

    fun insertConfig(outbox: String, authCode: String, inbox: String, title: String) {
        // 邮箱配置只保留最新一条：先清空再插入，避免历史配置无限累积
        emailConfigDao.deleteAll()
        val bean = EmailConfigBean()
        bean.outbox = outbox
        bean.authCode = authCode
        bean.inbox = inbox
        bean.title = title
        bean.createTime = System.currentTimeMillis().timestampToCompleteDate()
        emailConfigDao.insert(bean)
    }

    fun loadAll(): List<EmailConfigBean> {
        return emailConfigDao.loadAll()
    }

    /**
     * 加载最新一条邮箱配置（按创建时间倒序取第一条）。
     * loadAll().last() 依赖无序查询，取到的「最后一条」不可靠，请使用本方法。
     */
    fun loadEmailConfig(): EmailConfigBean? {
        return emailConfigDao.loadEmailConfig()
    }
}