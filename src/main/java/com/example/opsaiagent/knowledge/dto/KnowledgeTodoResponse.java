package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库待办聚合响应（P1）
 * 聚合：待审核文档 / 待沉淀案例 / 冲突待裁定 / 超期文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTodoResponse {

    /** 待审核文档列表 */
    private List<KnowledgeTodoItem> pendingDocs;
    /** 待沉淀案例列表 */
    private List<KnowledgeTodoItem> pendingCases;
    /** 冲突待裁定列表 */
    private List<KnowledgeTodoItem> conflicts;
    /** 超期文档列表 */
    private List<KnowledgeTodoItem> expiredDocs;

    /**
     * 待办条目（统一结构）
     */
    @Data
    @Builder
@NoArgsConstructor
@AllArgsConstructor
    public static class KnowledgeTodoItem {
        /** 业务对象 ID */
        private Long id;
        /** 标题 */
        private String title;
        /** 关联服务（文档取第一个 serviceId，案例取 service） */
        private String service;
        /** 对象类型：DOCUMENT / CASE / CONFLICT */
        private String itemType;
        /** 状态：文档 PENDING/EXPIRED、案例 PENDING、冲突 PENDING */
        private String status;
        /** 创建时间 */
        private LocalDateTime createdAt;
        /** 到期时间（仅超期文档有 reviewAt） */
        private LocalDateTime dueAt;
    }
}
