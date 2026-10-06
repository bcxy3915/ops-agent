package com.example.opsaiagent.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {

    /**
     * 删除指定时间之前的审计日志（分批删除使用）
     * MySQL 用 LIMIT，PostgreSQL 用 LIMIT 也支持
     *
     * @param before 截止时间
     * @param limit  最多删除条数
     * @return 实际删除的条数
     */
    @Delete("""
            DELETE FROM ops_audit_log WHERE id IN (
              SELECT id FROM ops_audit_log WHERE created_at < #{before}
              ORDER BY id LIMIT #{limit}
            )
            """)
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}