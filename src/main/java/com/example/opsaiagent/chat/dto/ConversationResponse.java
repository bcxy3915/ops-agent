package com.example.opsaiagent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话信息")
public class ConversationResponse {

    @Schema(description = "会话 ID（前端使用）")
    private String sessionId;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "消息数")
    private Integer messageCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActiveAt;

    @Schema(description = "最后一条消息摘要")
    private String lastMessage;
}