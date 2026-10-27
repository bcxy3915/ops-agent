package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.ConflictResolveRequest;
import com.example.opsaiagent.knowledge.dto.ConflictResolveResponse;
import com.example.opsaiagent.knowledge.dto.QualityCheckRequest;
import com.example.opsaiagent.knowledge.dto.QualityCheckResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeConflictEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeConflictMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.retrieval.HybridRetriever;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识文档上传质检服务测试（P1）
 * 覆盖：check 标题冲突/内容冲突（阈值跳过、docId 解析失败）、过时检测、脱敏预览；
 * resolve 三态裁定联动（KEEP_NEW/KEEP_OLD/MANUAL）、非法状态与非法决策异常。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识文档上传质检服务")
class KnowledgeQualityServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeConflictMapper conflictMapper;
    @Mock
    private HybridRetriever hybridRetriever;

    private KnowledgeQualityService service;

    /** 初始化 MyBatis-Plus 实体缓存，否则 Lambda wrapper 在单测环境无法解析 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeConflictEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 提供当前用户上下文（裁定人取 SecurityUtils.getCurrentUsername）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service = new KnowledgeQualityService(documentMapper, conflictMapper, hybridRetriever,
                new KnowledgeQueryScope(null));
    }

    // ==================== check ====================

    @Test
    @DisplayName("check：标题相同触发标题冲突，无向量检索参与")
    void check_titleConflict() {
        KnowledgeDocumentEntity old = doc(10L, "Nginx 部署手册");
        when(documentMapper.selectList(any())).thenReturn(List.of(old));
        when(hybridRetriever.retrieve(any(), any(Integer.class))).thenReturn(List.of());

        QualityCheckResponse resp = service.check(request("Nginx 部署手册", "内容"));

        assertThat(resp.getConflicts()).hasSize(1);
        QualityCheckResponse.ConflictCandidate c = resp.getConflicts().get(0);
        assertThat(c.getMatchedDocId()).isEqualTo(10L);
        assertThat(c.getConflictType()).isEqualTo("TITLE");
        assertThat(c.getSimilarity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("check：内容相似触发内容冲突，低于阈值被跳过")
    void check_contentConflictAndThreshold() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        KnowledgeDocumentEntity matched = doc(20L, "MySQL 调优");
        when(documentMapper.selectById(20L)).thenReturn(matched);
        when(hybridRetriever.retrieve(any(), any(Integer.class))).thenReturn(List.of(
                document(20L, "MySQL 调优"),
                document(21L, "Redis 调优"),
                document(22L, "ES 调优"),
                document(23L, "Kafka 调优")));

        QualityCheckResponse resp = service.check(request("MySQL 手册", "连接池参数设置"));

        // 前 3 个 similarity 1.0/0.8/0.6 >= 0.6 命中，第 4 个 0.4 跳过
        assertThat(resp.getConflicts()).hasSize(3);
        assertThat(resp.getConflicts()).allMatch(c -> "CONTENT".equals(c.getConflictType()));
        assertThat(resp.getConflicts()).extracting(QualityCheckResponse.ConflictCandidate::getMatchedDocId)
                .containsExactly(20L, 21L, 22L);
        assertThat(resp.getConflicts().get(0).getSimilarity()).isEqualTo(1.0);
        assertThat(resp.getConflicts().get(2).getSimilarity()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("check：标题冲突与内容冲突去重（检索命中同一文档不重复）")
    void check_deduplicateBetweenTitleAndContent() {
        KnowledgeDocumentEntity old = doc(30L, "Nginx 部署手册");
        when(documentMapper.selectList(any())).thenReturn(List.of(old));
        when(documentMapper.selectById(30L)).thenReturn(old);
        when(documentMapper.selectById(31L)).thenReturn(doc(31L, "Nginx 反向代理"));
        when(hybridRetriever.retrieve(any(), any(Integer.class))).thenReturn(List.of(
                document(30L, "Nginx 部署手册"),
                document(31L, "Nginx 反向代理")));

        QualityCheckResponse resp = service.check(request("Nginx 部署手册", "内容"));

        assertThat(resp.getConflicts()).hasSize(2);
    }

    @Test
    @DisplayName("check：检索结果 docId 缺失或非法时跳过")
    void check_skipUnparsableDocId() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(hybridRetriever.retrieve(any(), any(Integer.class))).thenReturn(List.of(
                document(null, "无 id"),
                document(40L, "正常 id")));
        when(documentMapper.selectById(40L)).thenReturn(doc(40L, "正常 id"));

        QualityCheckResponse resp = service.check(request("标题", "内容"));

        assertThat(resp.getConflicts()).hasSize(1);
        assertThat(resp.getConflicts().get(0).getMatchedDocId()).isEqualTo(40L);
    }

    @Test
    @DisplayName("check：同主题文档复核到期进入过时列表")
    void check_staleDocs() {
        KnowledgeDocumentEntity stale = doc(50L, "Nginx 部署手册");
        stale.setReviewAt(LocalDateTime.now().minusDays(1));
        when(documentMapper.selectList(any())).thenReturn(List.of(stale));
        when(hybridRetriever.retrieve(any(), any(Integer.class))).thenReturn(List.of());

        QualityCheckResponse resp = service.check(request("Nginx 部署手册", "内容"));

        assertThat(resp.getStaleDocs()).hasSize(1);
        assertThat(resp.getStaleDocs().get(0).getId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("check：内容含 IP/域名/实例时返回脱敏预览与敏感项")
    void check_sensitivePreview() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(hybridRetriever.retrieve(any(), any(Integer.class))).thenReturn(List.of());

        QualityCheckResponse resp = service.check(request("标题", "连接 192.168.1.10 的 api.example.com 完成"));

        assertThat(resp.getMaskedPreview().getMaskedContent()).isEqualTo("连接 *.*.*.* 的 ***.example.com 完成");
        assertThat(resp.getMaskedPreview().getSensitiveItems()).hasSize(2);
        assertThat(resp.getMaskedPreview().getSensitiveItems())
                .extracting(QualityCheckResponse.SensitiveItem::getType)
                .containsExactlyInAnyOrder("IP", "DOMAIN");
    }

    @Test
    @DisplayName("check：标题或内容为空抛出业务异常")
    void check_blankInputRejected() {
        assertThatThrownBy(() -> service.check(request("", "内容")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.check(request("标题", null)))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== resolve ====================

    @Test
    @DisplayName("resolve：KEEP_NEW 时旧文档作废、新文档记录裁定")
    void resolve_keepNew() {
        KnowledgeConflictEntity conflict = conflictEntity(100L, 1L, 2L, "PENDING");
        when(conflictMapper.selectById(100L)).thenReturn(conflict);
        KnowledgeDocumentEntity oldDoc = doc(2L, "旧文档");
        KnowledgeDocumentEntity newDoc = doc(1L, "新文档");
        when(documentMapper.selectById(2L)).thenReturn(oldDoc);
        when(documentMapper.selectById(1L)).thenReturn(newDoc);

        ConflictResolveResponse resp = service.resolve(100L, resolveRequest("KEEP_NEW"));

        assertThat(resp.getStatus()).isEqualTo("RESOLVED");
        assertThat(resp.getDecision()).isEqualTo("KEEP_NEW");
        assertThat(resp.getResolvedBy()).isEqualTo("admin");
        assertThat(conflict.getStatus()).isEqualTo("RESOLVED");
        assertThat(oldDoc.getTrustStatus()).isEqualTo("EXPIRED");
        assertThat(newDoc.getConflictStatus()).isEqualTo("KEEP_NEW");
        verify(conflictMapper).updateById(conflict);
        verify(documentMapper).updateById(oldDoc);
        verify(documentMapper).updateById(newDoc);
    }

    @Test
    @DisplayName("resolve：KEEP_OLD 时新文档作废")
    void resolve_keepOld() {
        KnowledgeConflictEntity conflict = conflictEntity(101L, 1L, 2L, "PENDING");
        when(conflictMapper.selectById(101L)).thenReturn(conflict);
        KnowledgeDocumentEntity newDoc = doc(1L, "新文档");
        when(documentMapper.selectById(1L)).thenReturn(newDoc);

        ConflictResolveResponse resp = service.resolve(101L, resolveRequest("KEEP_OLD"));

        assertThat(resp.getDecision()).isEqualTo("KEEP_OLD");
        assertThat(newDoc.getTrustStatus()).isEqualTo("EXPIRED");
        assertThat(newDoc.getConflictStatus()).isEqualTo("KEEP_OLD");
    }

    @Test
    @DisplayName("resolve：MANUAL 仅标记人工裁定，不使任何文档作废")
    void resolve_manual() {
        KnowledgeConflictEntity conflict = conflictEntity(102L, 1L, 2L, "PENDING");
        when(conflictMapper.selectById(102L)).thenReturn(conflict);
        KnowledgeDocumentEntity newDoc = doc(1L, "新文档");
        when(documentMapper.selectById(1L)).thenReturn(newDoc);

        ConflictResolveResponse resp = service.resolve(102L, resolveRequest("MANUAL"));

        assertThat(resp.getDecision()).isEqualTo("MANUAL");
        assertThat(newDoc.getTrustStatus()).isNotEqualTo("EXPIRED");
        assertThat(newDoc.getConflictStatus()).isEqualTo("MANUAL");
        verify(documentMapper, never()).updateById(org.mockito.ArgumentMatchers.argThat((KnowledgeDocumentEntity e) -> {
            return "EXPIRED".equals(e.getTrustStatus());
        }));
    }

    @Test
    @DisplayName("resolve：冲突不存在抛出业务异常")
    void resolve_conflictNotFound() {
        when(conflictMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.resolve(999L, resolveRequest("KEEP_NEW")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("resolve：非待裁定冲突抛出业务异常")
    void resolve_nonPendingRejected() {
        KnowledgeConflictEntity conflict = conflictEntity(103L, 1L, 2L, "RESOLVED");
        when(conflictMapper.selectById(103L)).thenReturn(conflict);

        assertThatThrownBy(() -> service.resolve(103L, resolveRequest("KEEP_NEW")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("resolve：非法决策抛出业务异常")
    void resolve_invalidDecisionRejected() {
        assertThatThrownBy(() -> service.resolve(100L, resolveRequest("DELETE_ALL")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.resolve(100L, new ConflictResolveRequest()))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== 辅助 ====================

    private QualityCheckRequest request(String title, String content) {
        QualityCheckRequest r = new QualityCheckRequest();
        r.setTitle(title);
        r.setContent(content);
        return r;
    }

    private ConflictResolveRequest resolveRequest(String decision) {
        ConflictResolveRequest r = new ConflictResolveRequest();
        r.setDecision(decision);
        return r;
    }

    private KnowledgeDocumentEntity doc(Long id, String title) {
        KnowledgeDocumentEntity e = new KnowledgeDocumentEntity();
        e.setId(id);
        e.setTitle(title);
        e.setTrustStatus("NORMAL");
        return e;
    }

    private KnowledgeConflictEntity conflictEntity(Long id, Long docId, Long matchedDocId, String status) {
        KnowledgeConflictEntity e = new KnowledgeConflictEntity();
        e.setId(id);
        e.setDocId(docId);
        e.setMatchedDocId(matchedDocId);
        e.setStatus(status);
        return e;
    }

    private Document document(Long docId, String title) {
        Map<String, Object> meta = new HashMap<>();
        if (docId != null) {
            meta.put("docId", docId);
        }
        meta.put("title", title);
        return new Document(title, meta);
    }
}
