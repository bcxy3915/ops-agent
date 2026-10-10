package com.example.opsaiagent.controller;

import com.example.opsaiagent.chat.dto.ConversationResponse;
import com.example.opsaiagent.chat.dto.MessageResponse;
import com.example.opsaiagent.chat.dto.RenameTitleRequest;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ConversationController 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("对话历史接口")
class ConversationControllerTest {

    @Mock
    private ConversationService conversationService;

    @InjectMocks
    private ConversationController controller;

    private final Authentication auth =
            new UsernamePasswordAuthenticationToken("admin", null, List.of());

    // ==================== listSessions ====================

    @Test
    @DisplayName("会话列表：返回成功响应")
    void listSessions() {
        ConversationResponse resp = ConversationResponse.builder()
                .sessionId("s1").title("会话1").build();
        when(conversationService.listConversations("admin")).thenReturn(List.of(resp));

        ApiResponse<List<ConversationResponse>> result = controller.listSessions(auth);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getSessionId()).isEqualTo("s1");
    }

    @Test
    @DisplayName("会话列表：无数据返回空列表")
    void listSessions_empty() {
        when(conversationService.listConversations("admin")).thenReturn(List.of());

        ApiResponse<List<ConversationResponse>> result = controller.listSessions(auth);

        assertThat(result.getData()).isEmpty();
    }

    // ==================== listMessages ====================

    @Test
    @DisplayName("会话消息：正常返回")
    void listMessages() {
        MessageResponse msg = MessageResponse.builder()
                .id(1L).role("user").content("你好").build();
        when(conversationService.listMessages("s1", "admin")).thenReturn(List.of(msg));

        ApiResponse<List<MessageResponse>> result = controller.listMessages("s1", auth);

        assertThat(result.getData()).hasSize(1);
    }

    // ==================== deleteSession ====================

    @Test
    @DisplayName("删除会话：成功返回 deleted")
    void deleteSession_success() {
        when(conversationService.deleteConversation("s1", "admin")).thenReturn(true);

        ApiResponse<Map<String, String>> result = controller.deleteSession("s1", auth);

        assertThat(result.getData()).containsEntry("deleted", "s1");
    }

    @Test
    @DisplayName("删除会话：会话不存在抛 BusinessException")
    void deleteSession_notFound() {
        when(conversationService.deleteConversation("sx", "admin")).thenReturn(false);

        assertThatThrownBy(() -> controller.deleteSession("sx", auth))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== renameSession ====================

    @Test
    @DisplayName("重命名：成功返回 sessionId 和新标题")
    void renameSession_success() {
        RenameTitleRequest req = new RenameTitleRequest();
        req.setTitle("新标题");
        when(conversationService.renameConversation("s1", "admin", "新标题")).thenReturn(true);

        ApiResponse<Map<String, String>> result = controller.renameSession("s1", req, auth);

        assertThat(result.getData())
                .containsEntry("sessionId", "s1")
                .containsEntry("title", "新标题");
    }

    @Test
    @DisplayName("重命名：会话不存在抛 BusinessException")
    void renameSession_notFound() {
        RenameTitleRequest req = new RenameTitleRequest();
        req.setTitle("新标题");
        when(conversationService.renameConversation("sx", "admin", "新标题")).thenReturn(false);

        assertThatThrownBy(() -> controller.renameSession("sx", req, auth))
                .isInstanceOf(BusinessException.class);
    }
}