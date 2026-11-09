package com.example.opsaiagent.knowledge.mapper;

import java.util.UUID;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeIndexTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 知识索引任务 Mapper。
 *
 * 任务领取使用 FOR UPDATE SKIP LOCKED + 租约，多实例下同一任务只会被一个 worker 执行；
 * 入队使用生成列唯一索引上的 UPSERT，同一文档同时只保留一条未完成任务。
 */
@Mapper
public interface KnowledgeIndexTaskMapper extends BaseMapper<KnowledgeIndexTaskEntity> {

    /**
     * 入队索引任务。
     * 同一文档已有 PENDING/RUNNING 任务时覆盖为最新版本并重置为待执行，
     * 旧任务即使正在执行，其完成回写也会因状态已变更而失效，由重跑覆盖结果。
     *
     * @param docId       文档 ID
     * @param docVersion  入队时的文档版本
     * @param vectorSource 向量 source 标识
     * @return 影响行数
     */
    @Insert("INSERT INTO knowledge_index_task "
            + "(doc_id, doc_version, vector_source, task_type, status, attempts, max_attempts, created_at, next_retry_at) "
            + "VALUES (#{docId}, #{docVersion}, #{vectorSource}, 'INDEX', 'PENDING', 0, 3, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)) "
            + "ON DUPLICATE KEY UPDATE "
            + "doc_version = VALUES(doc_version), vector_source = VALUES(vector_source), "
            + "status = 'PENDING', attempts = 0, error_msg = NULL, next_retry_at = UTC_TIMESTAMP(6), "
            + "started_at = NULL, finished_at = NULL, lease_until = NULL, claim_token=NULL")
    int enqueue(@Param("docId") Long docId,
                @Param("docVersion") Integer docVersion,
                @Param("vectorSource") String vectorSource);

    /**
     * 领取一条到期待执行的任务并加租约。
     * 租约需覆盖单个大文档的完整索引耗时，过短会导致多实例重复执行；
     * next_retry_at 用于失败退避，未到期的任务不会被提前领取。
     *
     * @param leaseMinutes 租约时长（分钟）
     * @return 领取到的任务；无任务时返回 null
     */
    default KnowledgeIndexTaskEntity claimOne(int leaseMinutes) {
        KnowledgeIndexTaskEntity task = pickOne();
        if (task == null) return null;
        String token = UUID.randomUUID().toString();
        start(task.getId(), leaseMinutes, token);
        task.setClaimToken(token);
        task.setStatus("RUNNING");
        task.setAttempts(task.getAttempts() + 1);
        return task;
    }

    /**
     * 锁定一条到期任务，必须在调用方事务内执行。
     * @return 任务，无入参
     */
    @Select("SELECT * FROM knowledge_index_task WHERE status='PENDING' AND next_retry_at<=UTC_TIMESTAMP(6) "
            + "ORDER BY next_retry_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
    KnowledgeIndexTaskEntity pickOne();

    /**
     * 标记领取并设置唯一令牌。
     * @param id 任务 ID
     * @param minutes 租约分钟
     * @param token 领取令牌
     * @return 影响行数
     */
    @Update("UPDATE knowledge_index_task SET status='RUNNING', started_at=UTC_TIMESTAMP(6), "
            + "lease_until=TIMESTAMPADD(MINUTE, #{minutes}, UTC_TIMESTAMP(6)), attempts=attempts+1, claim_token=#{token} WHERE id=#{id}")
    int start(@Param("id") Long id, @Param("minutes") int minutes, @Param("token") String token);

    /**
     * 按领取令牌锁定任务，防止过期 worker 写入或结束新任务。
     * @param id 任务 ID
     * @param token 令牌
     * @return 仍持有租约时返回任务，否则为空
     */
    @Select("SELECT * FROM knowledge_index_task WHERE id=#{id} AND claim_token=#{token} "
            + "AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE")
    KnowledgeIndexTaskEntity lockOwned(@Param("id") Long id, @Param("token") String token);

    /**
     * 回收租约超时的任务（worker 异常退出等情况），重新置为待执行。
     *
     * @return 回收条数
     */
    @Update("UPDATE knowledge_index_task SET status = 'PENDING', started_at = NULL, "
            + "lease_until = NULL, claim_token=NULL, next_retry_at = UTC_TIMESTAMP(6), error_msg = '租约超时，由调度回收' "
            + "WHERE status = 'RUNNING' AND lease_until < UTC_TIMESTAMP(6)")
    int recoverStale();

    /**
     * 结束任务。仅当任务仍处于 RUNNING 时生效，
     * 若执行期间被重新入队（状态回到 PENDING）则保留待执行状态。
     *
     * @param id       任务 ID
     * @param status   终态：SUCCESS / FAILED / SKIPPED
     * @param token 领取令牌
     * @param errorMsg 失败原因，成功时传 null
     * @return 影响行数
     */
    @Update("UPDATE knowledge_index_task SET status = #{status}, finished_at = UTC_TIMESTAMP(6), "
            + "lease_until = NULL, error_msg = #{errorMsg} "
            + "WHERE id = #{id} AND status = 'RUNNING' AND claim_token=#{token}")
    int finish(@Param("id") Long id,
               @Param("status") String status,
               @Param("errorMsg") String errorMsg, @Param("token") String token);

    /**
     * 失败重试：清空租约并回到待执行，保留 attempts 计数，按退避时间延后领取。
     *
     * @param id           任务 ID
     * @param errorMsg     本次失败原因
     * @param token 领取令牌
     * @param delaySeconds 退避秒数
     * @return 影响行数
     */
    @Update("UPDATE knowledge_index_task SET status = 'PENDING', started_at = NULL, "
            + "lease_until = NULL, error_msg = #{errorMsg}, "
            + "next_retry_at = TIMESTAMPADD(SECOND, #{delaySeconds}, UTC_TIMESTAMP(6)) "
            + "WHERE id = #{id} AND status = 'RUNNING' AND claim_token=#{token}")
    int retry(@Param("id") Long id,
              @Param("errorMsg") String errorMsg,
              @Param("delaySeconds") int delaySeconds, @Param("token") String token);
}
