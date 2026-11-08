package com.example.opsaiagent.chat.service;

/**
 * 一次问答的持久化归属与防陈旧写入令牌。
 * @param tenantId 可信租户
 * @param sessionId 前端会话标识
 * @param username 可信用户名
 * @param requestId 本轮唯一令牌
 * @param conversationId 会话实体 ID，删除后重建会变化
 * @param memoryId 本轮隔离的模型记忆标识
 */
public record ChatTurn(String tenantId, String sessionId, String username, String requestId,
                       String conversationId, String memoryId) {
}
