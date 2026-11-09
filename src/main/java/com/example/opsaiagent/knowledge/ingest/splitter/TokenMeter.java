package com.example.opsaiagent.knowledge.ingest.splitter;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * token 计量组件：CL100K_BASE 编码（与 TokenTextSplitter 同源）。
 *
 * 注意：CL100K 只是预算近似（实际模型为 text-embedding-v3，tokenizer 不一致），
 * 因此所有策略均以 maxChunkTokens 做硬上限、字符比例做硬切分兜底，不追求绝对精确。
 */
@Component
public class TokenMeter {

    private final Encoding encoding;

    public TokenMeter() {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /** 统计文本 token 数（空白文本为 0） */
    public int count(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * 超长单元的硬切分：按 token 比例换算字符边界做纯子串切分（无 encode/decode 往返，保证无损拼接）。
     * 仅用于无任何边界可退的病态长句（如超长无标点中文段落）。
     *
     * @param unitTokens 原单元 token 数（调用方已统计，避免重复编码）
     * @param windowTokens 每段目标 token 数
     */
    public List<String> hardSplitByTokens(String text, int unitTokens, int windowTokens) {
        int window = Math.max(windowTokens, 1);
        int pieces = (int) Math.ceil((double) unitTokens / window);
        if (pieces <= 1) {
            return List.of(text);
        }
        int charsPerPiece = Math.max((int) Math.ceil((double) text.length() / pieces), 1);
        List<String> result = new ArrayList<>(pieces);
        for (int start = 0; start < text.length(); start += charsPerPiece) {
            result.add(text.substring(start, Math.min(start + charsPerPiece, text.length())));
        }
        return result;
    }

    /** 供测试与策略复用：原始编码结果（避免重复编码开销） */
    public IntArrayList encode(String text) {
        return encoding.encode(text);
    }
}
