package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.KnowledgeVersionResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentVersionEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识文档版本服务单元测试（P2-①）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识文档版本服务")
class KnowledgeVersionServiceTest {

    @Mock
    private KnowledgeVersionMapper versionMapper;

    @Mock
    private KnowledgeDocumentMapper documentMapper;

    private KnowledgeVersionService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeVersionService(versionMapper, documentMapper, new KnowledgeQueryScope(null));
    }

    private KnowledgeDocumentVersionEntity version(Long id, Long docId, int v, String content, String reason) {
        KnowledgeDocumentVersionEntity e = new KnowledgeDocumentVersionEntity();
        e.setId(id);
        e.setDocId(docId);
        e.setVersion(v);
        e.setContentSnapshot(content);
        e.setChangedBy("ops");
        e.setChangeReason(reason);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    // ==================== recordSnapshot ====================

    @Test
    @DisplayName("recordSnapshot：无历史版本时从版本 1 开始")
    void recordSnapshot_firstVersion() {
        when(versionMapper.selectList(any())).thenReturn(List.of());

        int v = service.recordSnapshot(1L, "内容v1", "ops", KnowledgeVersionService.REASON_ONLINE_CREATE);

        assertThat(v).isEqualTo(1);
        verify(versionMapper).insert(any(KnowledgeDocumentVersionEntity.class));
    }

    @Test
    @DisplayName("recordSnapshot：已有历史版本时版本号 +1")
    void recordSnapshot_nextVersion() {
        when(versionMapper.selectList(any())).thenReturn(List.of(version(10L, 1L, 3, "内容v3", "UPDATE")));

        int v = service.recordSnapshot(1L, "内容v4", "ops", KnowledgeVersionService.REASON_UPDATE);

        assertThat(v).isEqualTo(4);
        verify(versionMapper).insert(any(KnowledgeDocumentVersionEntity.class));
    }

    @Test
    @DisplayName("recordSnapshot：docId 为空抛参数异常")
    void recordSnapshot_nullDocId() {
        assertThatThrownBy(() -> service.recordSnapshot(null, "内容", "ops", "UPDATE"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_DOCUMENT_INVALID);
    }

    // ==================== listVersions ====================

    @Test
    @DisplayName("listVersions：返回全部版本并按版本号降序")
    void listVersions_sorted() {
        when(versionMapper.selectList(any())).thenReturn(List.of(
                version(3L, 1L, 3, "内容v3", "UPDATE"),
                version(2L, 1L, 2, "内容v2", "UPDATE"),
                version(1L, 1L, 1, "内容v1", "ONLINE_CREATE")));

        List<KnowledgeVersionResponse> list = service.listVersions(1L);

        assertThat(list).hasSize(3);
        assertThat(list).extracting(KnowledgeVersionResponse::getVersion).containsExactly(3, 2, 1);
        assertThat(list.get(2).getChangeReason()).isEqualTo("ONLINE_CREATE");
        assertThat(list.get(0).getContentSnapshot()).isEqualTo("内容v3");
    }

    @Test
    @DisplayName("listVersions：无版本返回空列表")
    void listVersions_empty() {
        when(versionMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listVersions(9L)).isEmpty();
    }

    // ==================== requireVersion ====================

    @Test
    @DisplayName("requireVersion：命中并校验归属文档")
    void requireVersion_found() {
        KnowledgeDocumentVersionEntity v = version(5L, 1L, 2, "内容", "UPDATE");
        when(versionMapper.selectById(5L)).thenReturn(v);

        assertThat(service.requireVersion(1L, 5L)).isSameAs(v);
    }

    @Test
    @DisplayName("requireVersion：版本不存在抛业务异常")
    void requireVersion_notFound() {
        when(versionMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireVersion(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND);
    }

    @Test
    @DisplayName("requireVersion：版本不属于该文档抛业务异常")
    void requireVersion_wrongDoc() {
        when(versionMapper.selectById(5L)).thenReturn(version(5L, 2L, 1, "内容", "UPDATE"));

        assertThatThrownBy(() -> service.requireVersion(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND);
    }

    @Test
    @DisplayName("requireVersion：versionId 为空抛参数异常")
    void requireVersion_nullId() {
        assertThatThrownBy(() -> service.requireVersion(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KNOWLEDGE_VERSION_NOT_FOUND);
    }
}
