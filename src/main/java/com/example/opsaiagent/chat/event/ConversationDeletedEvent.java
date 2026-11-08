package com.example.opsaiagent.chat.event;

/**
 * 会话删除事件，仅在删除事务提交后清理当前进程的模型记忆。
 * @param memoryId 已包含可信租户、用户名和会话 ID 的记忆标识
 */
public record ConversationDeletedEvent(String memoryId) {
}
