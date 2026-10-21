package com.example.opsaiagent.oncall.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 值班人添加请求（P1 规格 7）
 */
@Data
public class OncallMemberCreateRequest {

    @NotNull(message = "值班人用户 ID 不能为空")
    private Long userId;

    @NotNull(message = "值班角色不能为空")
    @Pattern(regexp = "PRIMARY|BACKUP", message = "值班角色仅支持 PRIMARY / BACKUP")
    private String dutyRole;
}
