package com.example.opsaiagent.rbac.dto;

import com.example.opsaiagent.rbac.entity.RoleEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "角色信息")
public class RoleResponse {

    @Schema(description = "角色 ID")
    private Long id;

    @Schema(description = "角色编码")
    private String code;

    @Schema(description = "角色名称")
    private String name;

    @Schema(description = "作用域：PLATFORM / TENANT / DEPT")
    private String scope;

    @Schema(description = "是否内置角色")
    private Boolean builtin;

    /**
     * 实体转响应。
     *
     * @param entity 角色实体
     * @return 角色响应
     */
    public static RoleResponse from(RoleEntity entity) {
        if (entity == null) {
            return null;
        }
        return RoleResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .scope(entity.getScope())
                .builtin(entity.getBuiltin())
                .build();
    }
}
