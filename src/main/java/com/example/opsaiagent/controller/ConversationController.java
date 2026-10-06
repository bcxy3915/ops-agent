package com.example.opsaiagent.controller;

import com.example.opsaiagent.chat.dto.ConversationResponse;
import com.example.opsaiagent.chat.dto.MessageResponse;
import com.example.opsaiagent.chat.dto.RenameTitleRequest;
import com.example.opsaiagent.chat.service.ConversationService;
import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "对话历史", description = "会话与消息的查询、删除、重命名")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @Operation(summary = "查询会话列表", description = "查询当前用户的所有会话（不分页）")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/sessions")
    public ApiResponse<List<ConversationResponse>> listSessions(Authentication auth) {
        return ApiResponse.success(conversationService.listConversations(auth.getName()));
    }

    @Operation(summary = "查询会话消息", description = "获取指定会话的所有消息")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<MessageResponse>> listMessages(
            @PathVariable String sessionId,
            Authentication auth) {
        return ApiResponse.success(
                conversationService.listMessages(sessionId, auth.getName()));
    }

    @Operation(summary = "删除会话", description = "删除指定会话及其所有消息")
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, String>> deleteSession(
            @PathVariable String sessionId,
            Authentication auth) {
        boolean ok = conversationService.deleteConversation(sessionId, auth.getName());
        if (!ok) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "会话不存在或无权访问");
        }
        return ApiResponse.success(Map.of("deleted", sessionId));
    }

    @Operation(summary = "重命名会话")
    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/sessions/{sessionId}/title")
    public ApiResponse<Map<String, String>> renameSession(
            @PathVariable String sessionId,
            @RequestBody RenameTitleRequest request,
            Authentication auth) {
        boolean ok = conversationService.renameConversation(sessionId, auth.getName(), request.getTitle());
        if (!ok) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "会话不存在或无权访问");
        }
        return ApiResponse.success(Map.of("sessionId", sessionId, "title",  request.getTitle()));
    }
}