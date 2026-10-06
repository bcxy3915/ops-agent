package com.example.opsaiagent.chat.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ops_conversation")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 前端生成的会话 ID
     */
    private String sessionId;

    /**
     * 会话标题（首条用户消息前 20 字）
     */

    private String title;

    /**
     * 所属用户名
     */
    private String username;

    /**
     * 消息数（冗余）
     */
    private Integer messageCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 最后活跃时间（用于排序）
     */
    @TableField("last_active_at")
    private LocalDateTime lastActiveAt;
}