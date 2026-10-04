package com.example.opsaiagent.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.retrieval.KeywordRetriever;
import com.example.opsaiagent.service.OpsAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
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

    /**
     * 手动触发文档加载（也可在启动时自动执行）
     * @return 加载状态
     */
    @Operation(summary = "重新加载知识库文档",
            description = "手动触发文档加载，用于更新知识库内容。通常不需要调用，应用启动时自动加载。需要 ADMIN 角色")
    @RateLimit(key = "reload", limit = 3, period = 300, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(operation = "RELOAD_KNOWLEDGE", description = "重载知识库")
    @PostMapping("/knowledge/load")
    public Map<String,String> loadDocument(){
        return Map.of("status", "success", "message", "文档加载完成");
    }

    /**
     * 根据问题返回答案
     * @param question 问题
     * @return 答案
     */
    @Operation(
            summary = "同步问答",
            description = "一次性返回完整答案。适合后端调用、Postman 测试。"
    )
    @RateLimit(key = "ask", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @GetMapping("/ask")
    public Map<String, String> ask(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        String answer = opsAgentService.ask(sessionId, question);
        return Map.of(
                "sessionId", sessionId,
                "question", question,
                "answer", answer
        );
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
     * 根据问题返回答案（流式）
     * @param question 问题
     * @param sessionId 会话ID
     * @return 答案流
     */
    @Operation(
            summary = "流式问答（SSE）",
            description = "逐字返回答案，适合 Web 前端。返回 text/event-stream，需用 EventSource 消费。"
    )
    @RateLimit(key = "askStream", limit = 30, period = 60, dimension = RateLimit.Dimension.USER)
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + "; charset=utf-8")
    public Flux<String> askStream(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        return opsAgentService.askStream(sessionId, question)
                .concatWith(Flux.just("[DONE]")); //  // 拼一个结束标记
    }
}
