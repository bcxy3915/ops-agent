package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文档上传质检响应（P1）
 * 冲突检测 / 过时检测 / 敏感信息脱敏预览 三项结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityCheckResponse {

    /** 候选冲突列表（与已有文档标题或内容相似） */
    private List<ConflictCandidate> conflicts;
    /** 已过时文档列表（同主题文档复核已到期） */
    private List<StaleDocItem> staleDocs;
    /** 脱敏预览（内容 + 敏感项） */
    private MaskedPreview maskedPreview;

    /**
     * 冲突候选
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictCandidate {
        /** 冲突匹配到的已有文档 ID */
        private Long matchedDocId;
        /** 已有文档标题 */
        private String matchedTitle;
        /** 冲突类型：TITLE（标题相似）/ CONTENT（内容相似） */
        private String conflictType;
        /** 相似度（0~1） */
        private Double similarity;
    }

    /**
     * 已过时文档条目
     */
    @Data
    @Builder
@NoArgsConstructor
@AllArgsConstructor
    public static class StaleDocItem {
        /** 文档 ID */
        private Long id;
        /** 文档标题 */
        private String title;
        /** 复核到期时间 */
        private LocalDateTime reviewAt;
    }

    /**
     * 脱敏预览
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaskedPreview {
        /** 脱敏后的内容预览 */
        private String maskedContent;
        /** 检测到的敏感信息条目 */
        private List<SensitiveItem> sensitiveItems;
    }

    /**
     * 敏感信息条目（IP / DOMAIN / INSTANCE）
     */
    @Data
    @Builder
@NoArgsConstructor
@AllArgsConstructor
    public static class SensitiveItem {
        /** 敏感类型：IP / DOMAIN / INSTANCE */
        private String type;
        /** 原文 */
        private String raw;
        /** 脱敏展示值 */
        private String masked;
        /** 出现次数 */
        private Integer count;
    }
}
