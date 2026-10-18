package com.example.opsaiagent.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.chat.dto.AskResponse;
import com.example.opsaiagent.loader.DocumentLoader;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.retrieval.KeywordRetriever;
import com.example.opsaiagent.service.OpsAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
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
     * @return 加载统计
     */
    @Operation(summary = "重建知识库索引",
            description = "手动触发内置知识库增量重载：按内容指纹检测缺失/变更文档并加载，清理已移除文档的残留向量。需要 ADMIN 角色")
    @RateLimit(key = "reload", limit = 3, period = 300, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "RELOAD_KNOWLEDGE", description = "重建知识库索引")
    @PostMapping("/knowledge/reload")
    public Map<String, Object> reloadDocument() {
        return documentLoader.reload();
    }

    /**
     * 根据问题返回答案（含结构化引用）
     * @param question 问题
     * @return 答案 + citations
     */
    @Operation(
            summary = "同步问答",
            description = "一次性返回完整答案与引用 citations。适合后端调用、Postman 测试。"
    )
    @RateLimit(key = "ask", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @GetMapping("/ask")
    public AskResponse ask(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        return opsAgentService.askWithCitations(sessionId, question);
    }

    @Operation(summary = "[测试] BM25 独立检索")
    @GetMapping("/bm25-search")
    public Map<String, Object> bm25Search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        List<Document> docs = keywordRetriever.search(query, topK);
        List<Map<String, Object>> results = docs.stream()
                .map(d -> Map.<String, Object>of(
                        "source", d.getMetadata().getOrDefault("source", "unknown"),
                        "preview", d.getText().substring(0, Math.min(80, d.getText().length()))
                ))
                .toList();
        return Map.of("query", query, "count", docs.size(), "results", results);
    }

    /**
     * 根据问题返回答案（流式，SSE 增加 citations 事件）
     * @param question 问题
     * @param sessionId 会话ID
     * @return SSE 事件流：citations 事件 + 答案 token + [DONE]
     */
    @Operation(
            summary = "流式问答（SSE）",
            description = "先推送 citations 引用事件，再逐字返回答案。返回 text/event-stream，需用 EventSource 消费。"
    )
    @RateLimit(key = "askStream", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + "; charset=utf-8")
    public Flux<ServerSentEvent<String>> askStream(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        return opsAgentService.askStreamWithCitations(sessionId, question);
    }
}
