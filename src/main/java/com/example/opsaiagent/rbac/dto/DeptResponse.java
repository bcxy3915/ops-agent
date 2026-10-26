package com.example.opsaiagent.rbac.dto;

import com.example.opsaiagent.rbac.entity.DeptEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 部门响应（扁平列表，前端按 parentId 组树）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "部门信息")
public class DeptResponse {

    @Schema(description = "部门 ID")
    private Long id;

    @Schema(description = "租户 ID")
    private String tenantId;

    @Schema(description = "上级部门 ID")
    private Long parentId;

    @Schema(description = "部门编码")
    private String code;

    @Schema(description = "部门名称")
    private String name;

    @Schema(description = "物化路径")
    private String path;

    @Schema(description = "层级")
    private Integer level;

    @Schema(description = "同级排序")
    private Integer sort;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /**
     * 实体转响应。
     *
     * @param entity 部门实体
     * @return 部门响应
     */
    public static DeptResponse from(DeptEntity entity) {
        if (entity == null) {
            return null;
        }
        return DeptResponse.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .parentId(entity.getParentId())
                .code(entity.getCode())
                .name(entity.getName())
                .path(entity.getPath())
                .level(entity.getLevel())
                .sort(entity.getSort())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
