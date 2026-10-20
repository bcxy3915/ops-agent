package com.example.opsaiagent.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.alert.entity.AlertReadEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户告警已读 Mapper
 */
@Mapper
public interface AlertReadMapper extends BaseMapper<AlertReadEntity> {

    /**
     * 幂等插入已读记录（已存在则忽略，PostgreSQL ON CONFLICT）
     */
    @Insert("""
            INSERT INTO user_alert_read(user_id, alert_event_id, read_at)
            VALUES (#{userId}, #{alertEventId}, NOW())
            ON CONFLICT (user_id, alert_event_id) DO NOTHING
            """)
    int insertIgnore(@Param("userId") String userId, @Param("alertEventId") Long alertEventId);
}
