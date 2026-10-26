package com.example.opsaiagent.oncall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 值班人添加请求（P1 规格 7）
 */
@Data
public class OncallMemberCreateRequest {

    @NotBlank(message = "值班人用户 ID 不能为空")
    @Size(max = 32, message = "用户 ID 长度不能超过 32")
    private String userId;

    @NotBlank(message = "值班角色不能为空")
    @Pattern(regexp = "PRIMARY|BACKUP", message = "值班角色仅支持 PRIMARY / BACKUP")
    private String dutyRole;
}
