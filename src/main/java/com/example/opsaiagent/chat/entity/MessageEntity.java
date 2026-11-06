package com.example.opsaiagent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.opsaiagent.common.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "ops_message", autoResultMap = true)
public class MessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话id
     */
    private String sessionId;

    /**
     * 角色
     */
    private String role;

    /**
     * 内容
     */
    private String content;

    /**
     * AI 思考过程（仅 ASSISTANT）
     */
    private String reasoning;

    /**
     * 工具调用记录（JSON 字符串，仅 ASSISTANT，数据库列为 jsonb）
     */
    @TableField(value = "tool_calls", typeHandler = JsonbTypeHandler.class)
    private String toolCalls;

    /**
     * 消耗 token
     */
    private Integer tokensUsed;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}