package com.example.opsaiagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "重命名会话请求")
public class RenameTitleRequest {

    @Schema(description = "新标题", example = "内存泄漏排查")
    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题长度不能超过 128")
    private String title;
}