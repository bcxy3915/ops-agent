package com.example.opsaiagent.chat.controller;

import jakarta.validation.Valid;
import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.knowledge.loader.DocumentLoader;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.retrieval.KeywordRetriever;
import com.example.opsaiagent.chat.service.OpsAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 聊天接口类
 */
@Tag(name = "智能问答", description = "运维智能体的核心问答接口")
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class ChatController {

    private final OpsAgentService opsAgentService;
    private final KeywordRetriever keywordRetriever;
    private final DocumentLoader documentLoader;

    /**
     * 手动触发内置知识库文档重载（增量：缺失/变更才加载，清理残留）
     * 无入参。
     * @return 统一响应，包含加载统计
     */
    @Operation(summary = "重建知识库索引",
            description = "手动触发内置知识库增量重载：按内容指纹检测缺失/变更文档并加载，清理已移除文档的残留向量。需要 ADMIN 角色")
    @RateLimit(key = "reload", limit = 3, period = 300, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "RELOAD_KNOWLEDGE", description = "重建知识库索引")
    @PostMapping("/knowledge/reload")
    public ApiResponse<Map<String, Object>> reloadDocument() {
        Map<String, Object> result = documentLoader.reload();
        // 重建 BM25 内存索引（从检索投影全量构建，纳入 upload:/case: 文档与最新向量），
        // 使"重建索引"真正覆盖关键词检索，避免上传文档 publish 后长期检索不到
        keywordRetriever.rebuild();
        result.put("indexRebuilt", true);
        return ApiResponse.success(result);
    }

    /**
     * 根据问题返回答案（含结构化引用）
     * @param question 问题
     * @param sessionId 会话标识
     * @return 统一响应，包含答案与 citations
     */
    @Operation(
            summary = "同步问答",
            description = "通过 ApiResponse 返回完整答案与引用 citations，业务数据位于 data。适合后端调用、Postman 测试。"
    )
    @RateLimit(key = "ask", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @GetMapping("/ask")
    public ApiResponse<AskResponse> ask(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        return ApiResponse.success(opsAgentService.askWithCitations(sessionId, question));
    }

    /**
     * 独立验证关键词检索结果。
     * @param query 查询文本
     * @param topK 返回数量上限
     * @return 统一响应，包含查询、命中数量和结果预览
     */
    @Operation(summary = "[测试] BM25 独立检索")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/bm25-search")
    public ApiResponse<Map<String, Object>> bm25Search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        List<Document> docs = keywordRetriever.search(query, topK);
        List<Map<String, Object>> results = docs.stream()
                .map(d -> Map.<String, Object>of(
                        "source", d.getMetadata().getOrDefault("source", "unknown"),
                        "preview", d.getText().substring(0, Math.min(80, d.getText().length()))
                ))
                .toList();
        return ApiResponse.success(Map.of("query", query, "count", docs.size(), "results", results));
    }

    /**
     * 根据问题返回答案（流式，SSE 增加 citations 事件）
     * @param request 用户问题及会话标识
     * @return SSE 事件流：答案 token + citations 事件 + [DONE]
     */
    @Operation(
            summary = "流式问答（SSE）",
            description = "逐字返回答案后推送 citations 引用事件，最后发送 [DONE]。返回 text/event-stream，前端用 fetch + ReadableStream 消费（POST 传参，避免长文本撑爆请求头）。"
    )
    @RateLimit(key = "askStream", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + "; charset=utf-8")
    public Flux<ServerSentEvent<String>> askStream(
            @Valid @RequestBody AskStreamRequest request) {
        String sessionId = (request.getSessionId() == null || request.getSessionId().isBlank())
                ? "default"
                : request.getSessionId();
        return opsAgentService.askStreamWithCitations(sessionId, request.getQuestion())
                .map(this::withSseDataSeparator);
    }

    /**
     * 为每行 SSE 数据补齐协议分隔空格，防止标准解析器误删模型正文开头的空格。
     * Spring 的事件编码器直接输出 data:，客户端会按 SSE 规范移除字段值的首个空格。
     * 仅在 HTTP 输出边界处理，数据库答案和业务层 token 保持原样。
     * @param event 原始业务事件
     * @return 每行数据带一个协议分隔空格的传输事件
     */
    private ServerSentEvent<String> withSseDataSeparator(ServerSentEvent<String> event) {
        if (event.data() == null) { return event; }
        String data = " " + event.data().replace("\n", "\n ");
        var builder = ServerSentEvent.builder(data);
        if (event.id() != null) { builder.id(event.id()); }
        if (event.event() != null) { builder.event(event.event()); }
        if (event.retry() != null) { builder.retry(event.retry()); }
        if (event.comment() != null) { builder.comment(event.comment()); }
        return builder.build();
    }

    /** 流式问答请求体（长文本走 body，不走 URL query） */
    @Data
    @NoArgsConstructor
    public static class AskStreamRequest {

        @NotBlank(message = "问题不能为空")
        private String question;

        @Size(max = 64, message = "会话 ID 不能超过 64 个字符")
        private String sessionId;
    }
}
