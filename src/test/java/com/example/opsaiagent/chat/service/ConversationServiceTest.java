package com.example.opsaiagent.chat.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.chat.dto.ConversationResponse;
import com.example.opsaiagent.chat.dto.MessageResponse;
import com.example.opsaiagent.chat.entity.ConversationEntity;
import com.example.opsaiagent.chat.entity.MessageEntity;
import com.example.opsaiagent.chat.mapper.ConversationMapper;
import com.example.opsaiagent.chat.mapper.MessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationService 单元测试
 * 覆盖：ensureConversation / saveUserMessage / saveAssistantMessage
 *      / listConversations / listMessages / deleteConversation / renameConversation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话服务")
class ConversationServiceTest {

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageMapper messageMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ConversationService conversationService;

    // ==================== 辅助 ====================

    private ConversationEntity conv(String sessionId, String username) {
        ConversationEntity c = new ConversationEntity();
        c.setId("id-" + sessionId);
        c.setSessionId(sessionId);
        c.setUsername(username);
        c.setTitle("测试会话");
        c.setMessageCount(0);
        c.setCreatedAt(LocalDateTime.now());
        c.setLastActiveAt(LocalDateTime.now());
        return c;
    }

    private MessageEntity msg(Long id, String sessionId, String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // ==================== ensureConversation ====================

    @Nested
    @DisplayName("ensureConversation")
    class EnsureConversation {

        @Test
        @DisplayName("会话不存在：创建新会话")
        void createNew() {
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            conversationService.ensureConversation("sess-1", "admin", "你好");

            ArgumentCaptor<ConversationEntity> captor =
                    ArgumentCaptor.forClass(ConversationEntity.class);
            verify(conversationMapper).insert(captor.capture());

            ConversationEntity saved = captor.getValue();
            assertThat(saved.getSessionId()).isEqualTo("sess-1");
            assertThat(saved.getUsername()).isEqualTo("admin");
            assertThat(saved.getTitle()).isEqualTo("你好");
            assertThat(saved.getMessageCount()).isZero();
        }

        @Test
        @DisplayName("会话已存在：不重复创建")
        void existingNoop() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "admin"));

            conversationService.ensureConversation("sess-1", "admin", "你好");

            verify(conversationMapper, never()).insert(any(ConversationEntity.class));
        }

        @Test
        @DisplayName("首问超过 20 字：标题截断加 ...")
        void titleTruncated() {
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            String longQ = "这是一个非常长的问题用来验证标题是否会被正确截断处理很长的字符串";

            conversationService.ensureConversation("sess-1", "admin", longQ);

            ArgumentCaptor<ConversationEntity> captor =
                    ArgumentCaptor.forClass(ConversationEntity.class);
            verify(conversationMapper).insert(captor.capture());

            String title = captor.getValue().getTitle();
            assertThat(title).hasSize(23); // 20 + "..."
            assertThat(title).endsWith("...");
        }
    }

    // ==================== saveUserMessage ====================

    @Nested
    @DisplayName("saveUserMessage")
    class SaveUserMessage {

        @Test
        @DisplayName("插入用户消息并更新会话统计")
        void save() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "admin"));

            conversationService.saveUserMessage("sess-1", "你好");

            ArgumentCaptor<MessageEntity> captor =
                    ArgumentCaptor.forClass(MessageEntity.class);
            verify(messageMapper).insert(captor.capture());

            MessageEntity saved = captor.getValue();
            assertThat(saved.getSessionId()).isEqualTo("sess-1");
            assertThat(saved.getRole()).isEqualTo("USER");
            assertThat(saved.getContent()).isEqualTo("你好");

            verify(conversationMapper).updateById(any(ConversationEntity.class));
        }

        @Test
        @DisplayName("会话不存在：仍插入消息，但不更新会话")
        void noConversation() {
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            conversationService.saveUserMessage("sess-x", "hi");

            verify(messageMapper).insert(any(MessageEntity.class));
            verify(conversationMapper, never()).updateById(any(ConversationEntity.class));
        }
    }

    // ==================== saveAssistantMessage ====================

    @Nested
    @DisplayName("saveAssistantMessage")
    class SaveAssistantMessage {

        @Test
        @DisplayName("不带 toolCalls：正常插入")
        void simple() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "admin"));

            conversationService.saveAssistantMessage("sess-1", "回答", null, null);

            ArgumentCaptor<MessageEntity> captor =
                    ArgumentCaptor.forClass(MessageEntity.class);
            verify(messageMapper).insert(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo("ASSISTANT");
            assertThat(captor.getValue().getContent()).isEqualTo("回答");
            assertThat(captor.getValue().getToolCalls()).isNull();
        }

        @Test
        @DisplayName("带 toolCalls：序列化为 JSON")
        void withToolCalls() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "admin"));
            List<Map<String, Object>> tools = List.of(
                    Map.of("name", "queryServiceHealth", "result", "UP")
            );

            conversationService.saveAssistantMessage("sess-1", "回答", "思考中", tools);

            ArgumentCaptor<MessageEntity> captor =
                    ArgumentCaptor.forClass(MessageEntity.class);
            verify(messageMapper).insert(captor.capture());
            assertThat(captor.getValue().getReasoning()).isEqualTo("思考中");
            assertThat(captor.getValue().getToolCalls()).contains("queryServiceHealth");
        }

        @Test
        @DisplayName("异常被捕获，不冒泡")
        void exceptionSwallowed() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenThrow(new RuntimeException("DB error"));

            // ★ 断言 1：整个方法不应抛异常（异常被 catch 内部消化）
            assertThatCode(() ->
                    conversationService.saveAssistantMessage("sess-1", "x", null, null)
            ).doesNotThrowAnyException();

            // ★ 断言 2：insert 会被执行（异常发生在其之后）
            verify(messageMapper).insert(any(MessageEntity.class));

            // ★ 断言 3：updateById 不会被调用（updateConversationStats 在 selectOne 时就中断了）
            verify(conversationMapper, never()).updateById(any(ConversationEntity.class));
        }
    }

    // ==================== listConversations ====================

    @Nested
    @DisplayName("listConversations")
    class ListConversations {

        @Test
        @DisplayName("无会话：返回空列表")
        void empty() {
            when(conversationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

            List<ConversationResponse> result = conversationService.listConversations("admin");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常返回：带 lastMessage")
        void withLastMessage() {
            ConversationEntity c1 = conv("s1", "admin");
            when(conversationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(c1));
            when(messageMapper.selectMaps(any(Wrapper.class)))
                    .thenReturn(List.of(Map.of("session_id", "s1", "id", 100L)));
            when(messageMapper.selectBatchIds(any()))
                    .thenReturn(List.of(msg(100L, "s1", "ASSISTANT", "这是最后一条消息")));

            List<ConversationResponse> result = conversationService.listConversations("admin");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLastMessage()).isEqualTo("这是最后一条消息");
        }

        @Test
        @DisplayName("lastMessage 清理 Markdown 标记")
        void lastMessageCleanMarkdown() {
            ConversationEntity c1 = conv("s1", "admin");
            when(conversationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(c1));
            when(messageMapper.selectMaps(any(Wrapper.class)))
                    .thenReturn(List.of(Map.of("session_id", "s1", "id", 100L)));
            when(messageMapper.selectBatchIds(any()))
                    .thenReturn(List.of(msg(100L, "s1", "ASSISTANT", "**加粗** `代码` # 标题")));

            List<ConversationResponse> result = conversationService.listConversations("admin");

            assertThat(result.get(0).getLastMessage())
                    .doesNotContain("**")
                    .doesNotContain("`")
                    .doesNotContain("#");
        }

        @Test
        @DisplayName("lastMessage 超过 60 字符被截断")
        void lastMessageTruncated() {
            String longContent = "a".repeat(100);
            ConversationEntity c1 = conv("s1", "admin");
            when(conversationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(c1));
            when(messageMapper.selectMaps(any(Wrapper.class)))
                    .thenReturn(List.of(Map.of("session_id", "s1", "id", 100L)));
            when(messageMapper.selectBatchIds(any()))
                    .thenReturn(List.of(msg(100L, "s1", "ASSISTANT", longContent)));

            List<ConversationResponse> result = conversationService.listConversations("admin");

            assertThat(result.get(0).getLastMessage()).hasSize(63); // 60 + "..."
            assertThat(result.get(0).getLastMessage()).endsWith("...");
        }

        @Test
        @DisplayName("无最新消息：兜底「暂无预览内容」")
        void noLastMessage() {
            ConversationEntity c1 = conv("s1", "admin");
            when(conversationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(c1));
            when(messageMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

            List<ConversationResponse> result = conversationService.listConversations("admin");

            assertThat(result.get(0).getLastMessage()).isEqualTo("暂无预览内容");
        }
    }

    // ==================== listMessages ====================

    @Nested
    @DisplayName("listMessages")
    class ListMessages {

        @Test
        @DisplayName("会话不存在：返回空列表")
        void conversationNotFound() {
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            List<MessageResponse> result = conversationService.listMessages("sess-x", "admin");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("越权访问：返回空列表")
        void forbidden() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "other-user"));

            List<MessageResponse> result = conversationService.listMessages("sess-1", "admin");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常返回：role 转换 USER→user，ASSISTANT→assistant")
        void roleConversion() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "admin"));
            when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    msg(1L, "sess-1", "USER", "你好"),
                    msg(2L, "sess-1", "ASSISTANT", "你好，我是 Ops Agent")
            ));

            List<MessageResponse> result = conversationService.listMessages("sess-1", "admin");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getRole()).isEqualTo("user");
            assertThat(result.get(1).getRole()).isEqualTo("assistant");
        }

        @Test
        @DisplayName("toolCalls 反序列化为 tools 列表")
        void deserializeToolCalls() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "admin"));
            MessageEntity m = msg(1L, "sess-1", "ASSISTANT", "答");
            m.setToolCalls("[{\"name\":\"queryServiceHealth\",\"result\":\"UP\"}]");
            when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(m));

            List<MessageResponse> result = conversationService.listMessages("sess-1", "admin");

            assertThat(result.get(0).getTools()).hasSize(1);
            assertThat(result.get(0).getTools().get(0)).containsEntry("name", "queryServiceHealth");
        }

        @Test
        @DisplayName("toolCalls 非法 JSON：静默忽略，不影响消息返回")
        void invalidToolCalls() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "admin"));
            MessageEntity m = msg(1L, "sess-1", "ASSISTANT", "答");
            m.setToolCalls("not a json");
            when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(m));

            List<MessageResponse> result = conversationService.listMessages("sess-1", "admin");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTools()).isNull();
        }
    }

    // ==================== deleteConversation ====================

    @Nested
    @DisplayName("deleteConversation")
    class DeleteConversation {

        @Test
        @DisplayName("成功删除会话和消息")
        void success() {
            ConversationEntity c = conv("sess-1", "admin");
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(c);

            boolean result = conversationService.deleteConversation("sess-1", "admin");

            assertThat(result).isTrue();
            verify(conversationMapper).deleteById(c.getId());
            verify(messageMapper).delete(any(Wrapper.class));
        }

        @Test
        @DisplayName("会话不存在：返回 false")
        void notFound() {
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            boolean result = conversationService.deleteConversation("sess-x", "admin");

            assertThat(result).isFalse();
            verify(conversationMapper, never()).deleteById(any(String.class));
        }

        @Test
        @DisplayName("越权：返回 false，不删除")
        void forbidden() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "other"));

            boolean result = conversationService.deleteConversation("sess-1", "admin");

            assertThat(result).isFalse();
            verify(conversationMapper, never()).deleteById(any(String.class));
        }
    }

    // ==================== renameConversation ====================

    @Nested
    @DisplayName("renameConversation")
    class RenameConversation {

        @Test
        @DisplayName("成功重命名")
        void success() {
            ConversationEntity c = conv("sess-1", "admin");
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(c);

            boolean result = conversationService.renameConversation("sess-1", "admin", "新标题");

            assertThat(result).isTrue();
            ArgumentCaptor<ConversationEntity> captor =
                    ArgumentCaptor.forClass(ConversationEntity.class);
            verify(conversationMapper).updateById(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("新标题");
        }

        @Test
        @DisplayName("会话不存在：返回 false")
        void notFound() {
            when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            assertThat(conversationService.renameConversation("sess-x", "admin", "新标题"))
                    .isFalse();
        }

        @Test
        @DisplayName("越权：返回 false")
        void forbidden() {
            when(conversationMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(conv("sess-1", "other"));

            assertThat(conversationService.renameConversation("sess-1", "admin", "新标题"))
                    .isFalse();
            verify(conversationMapper, never()).updateById(any());
        }
    }
}