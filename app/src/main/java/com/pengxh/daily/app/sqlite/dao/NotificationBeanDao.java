package com.pengxh.daily.app.sqlite.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.pengxh.daily.app.sqlite.bean.NotificationBean;

import java.util.List;

@Dao
public interface NotificationBeanDao {
    @Query("DELETE FROM notice_record_table")
    void deleteAll();

    @Query("SELECT * FROM notice_record_table ORDER BY postTime DESC LIMIT :pageSize OFFSET :offset")
    List<NotificationBean> loadNoticeByTime(int pageSize, int offset);

    @Query("SELECT * FROM notice_record_table WHERE postTime LIKE :date || '%'")
    List<NotificationBean> loadCurrentDayNotice(String date);

    @Insert
    void insert(NotificationBean bean);

    // postTime 是 "yyyy-MM-dd HH:mm:ss" 定长字符串，字典序即时间序，可直接比较
    @Query("DELETE FROM notice_record_table WHERE postTime < :deadline")
    int deleteOlderThan(String deadline);
}
