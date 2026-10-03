package com.example.opsaiagent.controller;

import com.example.opsaiagent.service.OpsAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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

    /**
     * 手动触发文档加载（也可在启动时自动执行）
     * @return 加载状态
     */
    @Operation(summary = "重新加载知识库文档",
            description = "手动触发文档加载，用于更新知识库内容。通常不需要调用，应用启动时自动加载。")
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
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + "; charset=utf-8")
    public Flux<String> askStream(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        return opsAgentService.askStream(sessionId, question)
                .concatWith(Flux.just("[DONE]")); //  // 拼一个结束标记
    }
}
