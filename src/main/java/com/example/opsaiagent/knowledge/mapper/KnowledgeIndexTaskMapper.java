package com.example.opsaiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeIndexTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 知识索引任务 Mapper。
 *
 * 任务领取使用 FOR UPDATE SKIP LOCKED + 租约，多实例下同一任务只会被一个 worker 执行；
 * 入队使用部分唯一索引上的 UPSERT，同一文档同时只保留一条未完成任务。
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
            + "VALUES (#{docId}, #{docVersion}, #{vectorSource}, 'INDEX', 'PENDING', 0, 3, NOW(), NOW()) "
            + "ON CONFLICT (doc_id) WHERE status IN ('PENDING', 'RUNNING') DO UPDATE SET "
            + "doc_version = EXCLUDED.doc_version, vector_source = EXCLUDED.vector_source, "
            + "status = 'PENDING', attempts = 0, error_msg = NULL, next_retry_at = NOW(), "
            + "started_at = NULL, finished_at = NULL, lease_until = NULL")
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
    @Update("WITH picked AS ("
            + "SELECT id FROM knowledge_index_task WHERE status = 'PENDING' AND next_retry_at <= NOW() "
            + "ORDER BY next_retry_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) "
            + "UPDATE knowledge_index_task t SET status = 'RUNNING', started_at = NOW(), "
            + "lease_until = NOW() + (INTERVAL '1 minute' * #{leaseMinutes}), attempts = attempts + 1 "
            + "FROM picked WHERE t.id = picked.id RETURNING t.*")
    KnowledgeIndexTaskEntity claimOne(@Param("leaseMinutes") int leaseMinutes);

    /**
     * 回收租约超时的任务（worker 异常退出等情况），重新置为待执行。
     *
     * @return 回收条数
     */
    @Update("UPDATE knowledge_index_task SET status = 'PENDING', started_at = NULL, "
            + "lease_until = NULL, next_retry_at = NOW(), error_msg = '租约超时，由调度回收' "
            + "WHERE status = 'RUNNING' AND lease_until < NOW()")
    int recoverStale();

    /**
     * 结束任务。仅当任务仍处于 RUNNING 时生效，
     * 若执行期间被重新入队（状态回到 PENDING）则保留待执行状态。
     *
     * @param id       任务 ID
     * @param status   终态：SUCCESS / FAILED / SKIPPED
     * @param errorMsg 失败原因，成功时传 null
     * @return 影响行数
     */
    @Update("UPDATE knowledge_index_task SET status = #{status}, finished_at = NOW(), "
            + "lease_until = NULL, error_msg = #{errorMsg} "
            + "WHERE id = #{id} AND status = 'RUNNING'")
    int finish(@Param("id") Long id,
               @Param("status") String status,
               @Param("errorMsg") String errorMsg);

    /**
     * 失败重试：清空租约并回到待执行，保留 attempts 计数，按退避时间延后领取。
     *
     * @param id           任务 ID
     * @param errorMsg     本次失败原因
     * @param delaySeconds 退避秒数
     * @return 影响行数
     */
    @Update("UPDATE knowledge_index_task SET status = 'PENDING', started_at = NULL, "
            + "lease_until = NULL, error_msg = #{errorMsg}, "
            + "next_retry_at = NOW() + (INTERVAL '1 second' * #{delaySeconds}) "
            + "WHERE id = #{id} AND status = 'RUNNING'")
    int retry(@Param("id") Long id,
              @Param("errorMsg") String errorMsg,
              @Param("delaySeconds") int delaySeconds);
}
