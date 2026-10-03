package com.example.opsaiagent.controller;

import com.example.opsaiagent.service.OpsAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 聊天接口类
 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class ChatController {

    private final OpsAgentService opsAgentService;

    /**
     * 手动触发文档加载（也可在启动时自动执行）
     * @return 加载状态
     */
    @PostMapping("/load")
    public Map<String,String> loadDocument(){
        return Map.of("status", "success", "message", "文档加载完成");
    }

    /**
     * 根据问题返回答案
     * @param question 问题
     * @return 答案
     */
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
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + "; charset=utf-8")
    public Flux<String> askStream(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        return opsAgentService.askStream(sessionId, question)
                .concatWith(Flux.just("[DONE]")); //  // 拼一个结束标记
    }
}
