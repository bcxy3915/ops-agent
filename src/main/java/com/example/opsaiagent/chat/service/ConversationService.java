package com.example.opsaiagent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.opsaiagent.chat.dto.ConversationResponse;
import com.example.opsaiagent.chat.dto.MessageResponse;
import com.example.opsaiagent.chat.entity.ConversationEntity;
import com.example.opsaiagent.chat.entity.MessageEntity;
import com.example.opsaiagent.chat.mapper.ConversationMapper;
import com.example.opsaiagent.chat.mapper.MessageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 确保会话存在（首次提问时创建）
     * @param sessionId 会话 ID
     * @param username 用户名
     * @param firstQuestion 首次提问内容
     */
    @Transactional
    public void ensureConversation(String sessionId, String username, String firstQuestion) {
        ConversationEntity existing = findBySessionId(sessionId);
        if (existing != null) {
            return;
        }

        // 用问题前 20 字做标题
        String title = firstQuestion.length() > 20
                ? firstQuestion.substring(0, 20) + "..."
                : firstQuestion;

        LocalDateTime now = LocalDateTime.now();
        ConversationEntity conv = new ConversationEntity();
        conv.setSessionId(sessionId);
        conv.setUsername(username);
        conv.setTitle(title);
        conv.setMessageCount(0);
        conv.setLastActiveAt(now);

        conversationMapper.insert(conv);
        log.info("创建会话: sessionId={}, username={}, title={}", sessionId, username, title);
    }

    /**
     * 保存用户消息（同步，主流程调用）
     * @param sessionId 会话 ID
     * @param content 消息内容
     */
    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        MessageEntity msg = new MessageEntity();
        msg.setSessionId(sessionId);
        msg.setRole("USER");
        msg.setContent(content);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);

        // 更新会话
        updateConversationStats(sessionId, true);
    }

    /**
     * 保存 AI 回答（异步，SSE 流结束时调用）
     * @param sessionId 会话 ID
     * @param content 消息内容
     */
    @Async("conversationExecutor")
    @Transactional
    public void saveAssistantMessage(String sessionId, String content) {
        saveAssistantMessage(sessionId, content, null, null);
    }

    /**
     * 保存 AI 回答（异步，SSE 流结束时调用）
     * @param sessionId 会话 ID
     * @param content 消息内容
     * @param reasoning 原因
     * @param toolCalls 工具调用
     */
    @Async("conversationExecutor")
    @Transactional
    public void saveAssistantMessage(String sessionId, String content,
                                     String reasoning, List<Map<String, Object>> toolCalls) {
        try {
            MessageEntity msg = new MessageEntity();
            msg.setSessionId(sessionId);
            msg.setRole("ASSISTANT");
            msg.setContent(content);
            msg.setReasoning(reasoning);
            if (toolCalls != null && !toolCalls.isEmpty()) {
                msg.setToolCalls(objectMapper.writeValueAsString(toolCalls));
            }
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);

            updateConversationStats(sessionId, false);
        } catch (Exception e) {
            log.error("保存 AI 消息失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 更新会话统计
     * @param sessionId 会话 ID
     * @param incrementCount 是否增加消息计数
     */
    private void updateConversationStats(String sessionId, boolean incrementCount) {
        ConversationEntity conv = findBySessionId(sessionId);
        if (conv == null) return;

        conv.setLastActiveAt(LocalDateTime.now());
        if (incrementCount) {
            conv.setMessageCount((conv.getMessageCount() == null ? 0 : conv.getMessageCount()) + 1);
        } else {
            conv.setMessageCount((conv.getMessageCount() == null ? 0 : conv.getMessageCount()) + 1);
        }
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    /**
     * 查询用户的会话列表
     * @param username 用户名
     * @return 会话列表
     */
    public List<ConversationResponse> listConversations(String username) {
        // 1. 查询用户的会话列表（已按 lastActiveAt 倒序）
        List<ConversationEntity> list = conversationMapper.selectList(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getUsername, username)
                        .orderByDesc(ConversationEntity::getLastActiveAt)
        );

        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 提取所有 sessionId
        List<String> sessionIds = list.stream()
                .map(ConversationEntity::getSessionId)
                .toList();

        // 3. ★ 核心：查询每个 sessionId 的最新一条消息
        // 利用 id 自增特性，分组取 max(id)
        QueryWrapper<MessageEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("session_id", "max(id) as id")
                .in("session_id", sessionIds)
                .groupBy("session_id");

        List<Map<String, Object>> maxIdMaps = messageMapper.selectMaps(queryWrapper);

        // 提取出所有最新的消息 ID
        List<Long> latestMsgIds = maxIdMaps.stream()
                .map(map -> ((Number) map.get("id")).longValue())
                .toList();

        // 4. 批量查询这些最新消息的具体内容
        Map<String, String> lastMessageMap = new HashMap<>();
        if (!latestMsgIds.isEmpty()) {
            List<MessageEntity> latestMessages = messageMapper.selectBatchIds(latestMsgIds);
            for (MessageEntity msg : latestMessages) {
                String content = msg.getContent();
                if (content != null) {
                    // ★ 步骤 1：去掉 Markdown 标记（粗体、标题、代码块、列表、链接、行内代码）
                    String clean = content
                            .replaceAll("```[\\s\\S]*?```", " ")      // 代码块
                            .replaceAll("`[^`]*`", " ")                // 行内代码
                            .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1") // 链接和图片
                            .replaceAll("[#>*_~\\-]+", " ")            // 标题、引用、列表、粗斜体
                            .replaceAll("\\s+", " ")                    // 连续空白合并成单空格
                            .trim();

                    // ★ 步骤 2：截断到 60 字符
                    String preview = clean.length() > 60
                            ? clean.substring(0, 60) + "..."
                            : clean;

                    // 如果清洗后是空字符串，给个兜底
                    if (preview.isEmpty()) {
                        preview = "暂无预览内容";
                    }

                    lastMessageMap.put(msg.getSessionId(), preview);
                }
            }
        }

        // 5. 组装最终结果
        return list.stream()
                .map(e -> toConversationResponse(e, lastMessageMap.getOrDefault(e.getSessionId(), "暂无预览内容")))
                .toList();
    }

    /**
     * 查询会话的所有消息
     * @param sessionId 会话 ID
     * @param username 用户名
     * @return 消息列表
     */
    public List<MessageResponse> listMessages(String sessionId, String username) {
        ConversationEntity conv = findBySessionId(sessionId);
        if (conv == null || !conv.getUsername().equals(username)) {
            return List.of();
        }

        List<MessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getSessionId, sessionId)
                        .orderByAsc(MessageEntity::getCreatedAt)
        );

        return messages.stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /**
     * 删除会话（连带消息）
     * @param sessionId 会话 ID
     * @param username 用户名
     * @return 是否删除成功
     */
    @Transactional
    public boolean deleteConversation(String sessionId, String username) {
        ConversationEntity conv = findBySessionId(sessionId);
        if (conv == null || !conv.getUsername().equals(username)) {
            return false;
        }

        conversationMapper.deleteById(conv.getId());
        messageMapper.delete(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getSessionId, sessionId));
        log.info("删除会话: sessionId={}, username={}", sessionId, username);
        return true;
    }

    /**
     * 重命名会话
     * @param sessionId 会话 ID
     * @param username 用户名
     * @param newTitle 新标题
     * @return 是否重命名成功
     */
    public boolean renameConversation(String sessionId, String username, String newTitle) {
        ConversationEntity conv = findBySessionId(sessionId);
        if (conv == null || !conv.getUsername().equals(username)) {
            return false;
        }
        conv.setTitle(newTitle);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
        return true;
    }

    /**
     * 根据会话 ID 查询会话
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    private ConversationEntity findBySessionId(String sessionId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getSessionId, sessionId)
                        .last("LIMIT 1")
        );
    }

    /**
     * 转换会话实体为会话响应
     * @param e 会话实体
     * @param lastMessage 最后一条消息摘要
     * @return 会话响应
     */
    private ConversationResponse toConversationResponse(ConversationEntity e, String lastMessage) {
        return ConversationResponse.builder()
                .sessionId(e.getSessionId())
                .title(e.getTitle())
                .messageCount(e.getMessageCount())
                .createdAt(e.getCreatedAt())
                .lastActiveAt(e.getLastActiveAt())
                .lastMessage(lastMessage)
                .build();
    }

    /**
     * 转换消息实体为消息响应
     * @param e 消息实体
     * @return 消息响应
     */
    private MessageResponse toMessageResponse(MessageEntity e) {
        List<Map<String, Object>> tools = null;
        if (e.getToolCalls() != null && !e.getToolCalls().isBlank()) {
            try {
                tools = objectMapper.readValue(e.getToolCalls(),
                        new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception ex) {
                log.warn("解析 toolCalls 失败: {}", e.getToolCalls());
            }
        }

        return MessageResponse.builder()
                .id(e.getId())
                .role("USER".equals(e.getRole()) ? "user" : "assistant")
                .content(e.getContent())
                .reasoning(e.getReasoning())
                .tools(tools)
                .createdAt(e.getCreatedAt())
                .build();
    }
}