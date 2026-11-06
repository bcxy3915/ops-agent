package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeIndexTaskEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeIndexTaskMapper;
import com.example.opsaiagent.retrieval.KnowledgeIndexUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 知识索引任务服务。
 *
 * 审核通过、案例确认等业务事务只把索引任务写入 knowledge_index_task 并立即提交，
 * 分块与向量化（耗时最长、依赖外部 embedding）由后台 worker 在独立事务中执行，
 * 避免 embedding 调用长时间占用数据库事务与连接。
 *
 * 多实例下通过 FOR UPDATE SKIP LOCKED 领取任务，并用租约回收异常退出的任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexTaskService {

    /** 待执行 */
    public static final String STATUS_PENDING = "PENDING";
    /** 执行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 执行成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 重试耗尽后失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 文档已变更或不再需要索引，跳过 */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** 默认最大尝试次数（与表默认值一致） */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 失败原因落库前的截断长度 */
    private static final int ERROR_MSG_MAX_LENGTH = 1000;

    /** 重试退避上限（秒），避免大间隔影响失败文档的恢复速度 */
    private static final int MAX_RETRY_BACKOFF_SECONDS = 300;

    private final KnowledgeIndexTaskMapper taskMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentIndexer indexer;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventPublisher;

    /** 每轮调度的最多处理任务数 */
    @Value("${ops-agent.knowledge.index.max-per-round:3}")
    private int maxPerRound = 3;

    /** 任务租约时长（分钟），需覆盖单个大文档的完整索引耗时 */
    @Value("${ops-agent.knowledge.index.lease-minutes:30}")
    private int leaseMinutes = 30;

    /** 首次重试退避（秒），按 attempts 线性递增 */
    @Value("${ops-agent.knowledge.index.retry-backoff-seconds:30}")
    private int retryBackoffSeconds = 30;

    /**
     * 入队索引任务（在调用方事务内执行，随业务状态一起提交）。
     *
     * @param docId        文档 ID
     * @param docVersion   文档版本，执行时用于幂等校验
     * @param vectorSource 向量 source 标识
     */
    public void enqueue(Long docId, Integer docVersion, String vectorSource) {
        if (docId == null || vectorSource == null) {
            log.warn("索引任务参数不完整，跳过入队: docId={}, source={}", docId, vectorSource);
            return;
        }
        taskMapper.enqueue(docId, docVersion == null ? 1 : docVersion, vectorSource);
        log.info("知识索引任务入队: docId={}, version={}, source={}", docId, docVersion, vectorSource);
    }

    /**
     * 查询文档最近一条索引任务（供前端轮询进度使用）。
     *
     * @param docId 文档 ID
     * @return 最近一条任务；无任务时返回 null
     */
    public KnowledgeIndexTaskEntity findLatest(Long docId) {
        if (docId == null) {
            return null;
        }
        List<KnowledgeIndexTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeIndexTaskEntity>()
                        .eq(KnowledgeIndexTaskEntity::getDocId, docId)
                        .orderByDesc(KnowledgeIndexTaskEntity::getId)
                        .last("LIMIT 1"));
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    /**
     * 定时扫描并执行索引任务。
     */
    @Scheduled(fixedDelayString = "${ops-agent.knowledge.index.poll-interval-ms:5000}")
    public void poll() {
        try {
            int recovered = taskMapper.recoverStale();
            if (recovered > 0) {
                log.warn("回收 {} 个租约超时的知识索引任务", recovered);
            }
        } catch (Exception e) {
            log.warn("回收知识索引任务失败: {}", e.getMessage());
        }

        for (int i = 0; i < maxPerRound; i++) {
            KnowledgeIndexTaskEntity task;
            try {
                task = taskMapper.claimOne(leaseMinutes);
            } catch (Exception e) {
                log.warn("领取知识索引任务失败: {}", e.getMessage());
                return;
            }
            if (task == null) {
                return;
            }
            runTask(task);
        }
    }

    /**
     * 在独立事务中执行一个任务并回写状态。
     *
     * @param task 已领取的任务
     */
    void runTask(KnowledgeIndexTaskEntity task) {
        String result;
        try {
            result = new TransactionTemplate(transactionManager).execute(status -> execute(task, status));
        } catch (Exception e) {
            handleFailure(task, e);
            return;
        }
        taskMapper.finish(task.getId(), result, null);
        log.info("知识索引任务{}: id={}, docId={}, source={}", result, task.getId(),
                task.getDocId(), task.getVectorSource());
        if (STATUS_SUCCESS.equals(result)) {
            // 向量已写入，通知关键词索引刷新，避免新文档在 BM25 路径长期缺席
            eventPublisher.publishEvent(new KnowledgeIndexUpdatedEvent(task.getDocId()));
        }
    }

    /**
     * 事务内执行：校验任务有效性 → 重建索引 → 复核是否被并发修改。
     *
     * @param task     任务
     * @param txStatus 事务状态，用于在并发变更时回滚
     * @return 任务终态 SUCCESS / SKIPPED
     */
    private String execute(KnowledgeIndexTaskEntity task, TransactionStatus txStatus) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(task.getDocId());
        if (!isIndexable(doc, task)) {
            return STATUS_SKIPPED;
        }
        indexer.reindex(doc, task.getVectorSource());
        // 执行期间文档可能被驳回或再次编辑：此时本次写入的内容已过期，回滚避免留下脏索引
        KnowledgeDocumentEntity latest = documentMapper.selectById(task.getDocId());
        if (!isIndexable(latest, task)) {
            txStatus.setRollbackOnly();
            log.info("索引任务执行期间文档已变更，回滚本次写入: docId={}", task.getDocId());
            return STATUS_SKIPPED;
        }
        // 与分块、向量写入同一事务：置 PUBLISHED 才算真正可检索，页面与检索能力保持一致
        latest.setPublishStatus(KnowledgeDocumentService.PUBLISH_PUBLISHED);
        latest.setPublishError(null);
        latest.setPublishedAt(LocalDateTime.now());
        documentMapper.updateById(latest);
        return STATUS_SUCCESS;
    }

    /**
     * 文档是否仍允许索引：存在、审核已通过、且版本与入队时一致。
     *
     * <p>此处只校验审核结论，不校验 trust_status：内容过期由检索侧按 trustStatus 过滤，
     * 不应因此阻止已通过审核的文档完成索引。</p>
     */
    private boolean isIndexable(KnowledgeDocumentEntity doc, KnowledgeIndexTaskEntity task) {
        return doc != null
                && KnowledgeDocumentService.REVIEW_APPROVED.equals(doc.getReviewStatus())
                && Objects.equals(task.getDocVersion(), doc.getVersion());
    }

    private void handleFailure(KnowledgeIndexTaskEntity task, Exception e) {
        String errorMsg = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        int attempts = task.getAttempts() == null ? 1 : task.getAttempts();
        int maxAttempts = task.getMaxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : task.getMaxAttempts();
        if (attempts >= maxAttempts) {
            taskMapper.finish(task.getId(), STATUS_FAILED, errorMsg);
            markPublishFailed(task, errorMsg);
            log.error("知识索引任务失败（已达最大重试 {} 次）: id={}, docId={}", maxAttempts,
                    task.getId(), task.getDocId(), e);
        } else {
            int delaySeconds = backoffSeconds(attempts);
            taskMapper.retry(task.getId(), errorMsg, delaySeconds);
            log.warn("知识索引任务失败，{}s 后重试: id={}, docId={}, attempts={}/{}", delaySeconds,
                    task.getId(), task.getDocId(), attempts, maxAttempts, e);
        }
    }

    /**
     * 重试退避：按已尝试次数线性递增，并限制上限。
     *
     * @param attempts 已尝试次数
     * @return 退避秒数
     */
    private int backoffSeconds(int attempts) {
        long seconds = (long) retryBackoffSeconds * Math.max(attempts, 1);
        return (int) Math.min(seconds, MAX_RETRY_BACKOFF_SECONDS);
    }

    /**
     * 重试耗尽后回写文档入库失败状态（只动 publish 维度，不改写审核结论）。
     *
     * <p>仅当文档仍为 APPROVED 且版本与任务一致时回写，避免覆盖编辑/驳回后的新状态。</p>
     *
     * @param task     任务
     * @param errorMsg 失败原因
     */
    private void markPublishFailed(KnowledgeIndexTaskEntity task, String errorMsg) {
        try {
            KnowledgeDocumentEntity doc = documentMapper.selectById(task.getDocId());
            if (doc != null
                    && KnowledgeDocumentService.REVIEW_APPROVED.equals(doc.getReviewStatus())
                    && Objects.equals(task.getDocVersion(), doc.getVersion())) {
                doc.setPublishStatus(KnowledgeDocumentService.PUBLISH_FAILED);
                doc.setPublishError(errorMsg);
                doc.setPublishedAt(null);
                documentMapper.updateById(doc);
            }
        } catch (Exception e) {
            log.warn("回写文档入库失败状态异常: docId={}, err={}", task.getDocId(), e.getMessage());
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > ERROR_MSG_MAX_LENGTH
                ? message.substring(0, ERROR_MSG_MAX_LENGTH) : message;
    }
}
