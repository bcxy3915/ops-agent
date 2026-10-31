package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeIndexTaskEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeIndexTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识索引任务服务单元测试
 * 覆盖：无任务空转、版本一致执行成功、版本变化跳过、失败重试、重试耗尽。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识索引任务服务")
class KnowledgeIndexTaskServiceTest {

    @Mock
    private KnowledgeIndexTaskMapper taskMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeDocumentIndexer indexer;
    @Mock
    private PlatformTransactionManager transactionManager;

    private KnowledgeIndexTaskService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeIndexTaskService(taskMapper, documentMapper, indexer, transactionManager);
        org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
    }

    private KnowledgeIndexTaskEntity task(Integer docVersion, int attempts, int maxAttempts) {
        KnowledgeIndexTaskEntity t = new KnowledgeIndexTaskEntity();
        t.setId(10L);
        t.setDocId(1L);
        t.setDocVersion(docVersion);
        t.setVectorSource("upload:1");
        t.setAttempts(attempts);
        t.setMaxAttempts(maxAttempts);
        return t;
    }

    private KnowledgeDocumentEntity verifiedDoc(String version) {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(1L);
        doc.setVersion(Integer.parseInt(version));
        doc.setTrustStatus(KnowledgeDocumentService.TRUST_VERIFIED);
        doc.setReviewStatus(KnowledgeDocumentService.REVIEW_APPROVED);
        doc.setRawContent("内容");
        return doc;
    }

    @Test
    @DisplayName("poll：无待执行任务时直接返回")
    void poll_noTask() {
        when(taskMapper.recoverStale()).thenReturn(0);
        when(taskMapper.claimOne(anyInt())).thenReturn(null);

        service.poll();

        verify(indexer, never()).reindex(any(), anyString());
    }

    @Test
    @DisplayName("执行成功：文档版本一致则重建索引并标记 SUCCESS")
    void runTask_success() {
        KnowledgeIndexTaskEntity t = task(1, 1, 3);
        when(documentMapper.selectById(1L)).thenReturn(verifiedDoc("1"));

        service.runTask(t);

        verify(indexer).reindex(any(), eq("upload:1"));
        verify(taskMapper).finish(10L, KnowledgeIndexTaskService.STATUS_SUCCESS, null);
        ArgumentCaptor<KnowledgeDocumentEntity> captor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPublishStatus())
                .isEqualTo(KnowledgeDocumentService.PUBLISH_PUBLISHED);
        assertThat(captor.getValue().getPublishedAt()).isNotNull();
        assertThat(captor.getValue().getPublishError()).isNull();
    }

    @Test
    @DisplayName("版本已变化：跳过且不重建索引")
    void runTask_versionChanged() {
        KnowledgeIndexTaskEntity t = task(1, 1, 3);
        when(documentMapper.selectById(1L)).thenReturn(verifiedDoc("2"));

        service.runTask(t);

        verify(indexer, never()).reindex(any(), anyString());
        verify(taskMapper).finish(10L, KnowledgeIndexTaskService.STATUS_SKIPPED, null);
    }

    @Test
    @DisplayName("文档不存在：跳过")
    void runTask_docMissing() {
        KnowledgeIndexTaskEntity t = task(1, 1, 3);
        when(documentMapper.selectById(1L)).thenReturn(null);

        service.runTask(t);

        verify(indexer, never()).reindex(any(), anyString());
        verify(taskMapper).finish(10L, KnowledgeIndexTaskService.STATUS_SKIPPED, null);
    }

    @Test
    @DisplayName("执行失败未达上限：重置为待执行并保留错误原因")
    void runTask_retry() {
        KnowledgeIndexTaskEntity t = task(1, 1, 3);
        when(documentMapper.selectById(1L)).thenReturn(verifiedDoc("1"));
        doThrow(new IllegalStateException("embedding 调用失败"))
                .when(indexer).reindex(any(), anyString());

        service.runTask(t);

        verify(taskMapper).retry(eq(10L), anyString());
        verify(taskMapper, never()).finish(eq(10L), anyString(), any());
    }

    @Test
    @DisplayName("执行失败已达上限：标记 FAILED")
    void runTask_failedExhausted() {
        KnowledgeIndexTaskEntity t = task(1, 3, 3);
        when(documentMapper.selectById(1L)).thenReturn(verifiedDoc("1"));
        doThrow(new IllegalStateException("embedding 调用失败"))
                .when(indexer).reindex(any(), anyString());

        service.runTask(t);

        verify(taskMapper).finish(eq(10L), eq(KnowledgeIndexTaskService.STATUS_FAILED), anyString());
        verify(taskMapper, never()).retry(anyLong(), anyString());
        ArgumentCaptor<KnowledgeDocumentEntity> captor = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPublishStatus())
                .isEqualTo(KnowledgeDocumentService.PUBLISH_FAILED);
        assertThat(captor.getValue().getPublishError()).contains("embedding 调用失败");
        // 审核结论不被索引失败推翻
        assertThat(captor.getValue().getReviewStatus()).isEqualTo(KnowledgeDocumentService.REVIEW_APPROVED);
    }
}
