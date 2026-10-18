package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 知识覆盖度响应（P1）：按服务统计知识文档覆盖情况
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCoverageResponse {

    /** 已注册服务总数 */
    private Long totalServices;
    /** 有知识文档关联的服务数 */
    private Long coveredServices;
    /** 服务级覆盖率（covered / total，0~1） */
    private Double coverageRate;
    /** 各服务明细 */
    private List<ServiceCoverageItem> details;

    /**
     * 单个服务覆盖明细
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceCoverageItem {
        /** 服务名 */
        private String service;
        /** 关联知识文档数 */
        private Long docCount;
        /** 已审核（VERIFIED）文档数 */
        private Long verifiedCount;
    }
}
