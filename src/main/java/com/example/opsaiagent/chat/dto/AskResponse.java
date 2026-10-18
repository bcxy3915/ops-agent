package com.example.opsaiagent.chat.dto;

import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 问答响应（T23/T24）
 * 同步问答返回：答案 + 结构化引用（citations）。
 * 引用字段：docId/title/layer/trustStatus/score/version/section，供前端渲染引用列表。
 */
@Data
@Builder
public class AskResponse {

    private String sessionId;
    private String question;
    private String answer;

    /** 引用来源列表（可能为空） */
    private List<RetrievalHitResponse> citations;
}
