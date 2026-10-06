package com.example.opsaiagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "单条消息")
public class MessageResponse {

    @Schema(description = "消息 ID")
    private Long id;

    @Schema(description = "角色：user / assistant")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "AI 思考过程")
    private String reasoning;

    @Schema(description = "工具调用列表")
    private List<Map<String, Object>> tools;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}