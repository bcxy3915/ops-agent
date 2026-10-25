package com.example.opsaiagent.rbac.dto;

import com.example.opsaiagent.rbac.entity.PermissionEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权限点响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "权限点信息")
public class PermissionResponse {

    @Schema(description = "权限 ID")
    private Long id;

    @Schema(description = "权限编码，如 knowledge:review")
    private String code;

    @Schema(description = "权限名称")
    private String name;

    @Schema(description = "所属模块")
    private String module;

    /**
     * 实体转响应。
     *
     * @param entity 权限实体
     * @return 权限响应
     */
    public static PermissionResponse from(PermissionEntity entity) {
        if (entity == null) {
            return null;
        }
        return PermissionResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .module(entity.getModule())
                .build();
    }
}
