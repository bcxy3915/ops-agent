package com.example.opsaiagent.knowledge.ingest.splitter;

import com.example.opsaiagent.knowledge.model.Document;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Token 兜底策略（order=999）：直接使用 JTokkit 保留旧切分口径。
 * 兼容规则参考 Spring AI 2.0.2，来源及 Apache-2.0 许可见 docs/licenses/spring-ai-compatibility.md。
 *
 * 仅在以下场景使用，不参与 supports 自然匹配：
 * - 配置回退（default-strategy / markdown.strategy 设为 token）；
 * - 显式 strategyOverride=token；
 * - 其他策略抛异常时由 Resolver 降级（切分永不失败）。
 */
@Component
public class TokenFallbackSplitter implements TextChunkStrategy {

    public static final String NAME = "token";

    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    /**
     * 返回策略标识。
     * @return token 策略名
     */
    @Override
    public String name() {
        return NAME;
    }

    /**
     * 返回兜底优先级。
     * @return 最后执行的优先级
     */
    @Override
    public int order() {
        return 999;
    }

    /**
     * 禁止自然路由命中兜底策略。
     * @param context 文档上下文
     * @return 始终为 false
     */
    @Override
    public boolean supports(SplitContext context) {
        return false;
    }

    /**
     * 按原 token 兜底规则切分项目文档。
     * @param docs 待切分文档
     * @param options 路由切分选项，本策略沿用固定兜底参数
     * @return 携带原元数据的文档块
     */
    @Override
    public List<Document> split(List<Document> docs, SplitOptions options) {
        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            List<String> pieces = splitText(doc.getText() == null ? "" : doc.getText());
            for (int index = 0; index < pieces.size(); index++) {
                var metadata = new HashMap<String, Object>();
                doc.getMetadata().forEach((key, value) -> {
                    if (key != null && value != null) {
                        metadata.put(key, value);
                    }
                });
                metadata.put("parent_document_id", doc.getId());
                metadata.put("chunk_index", index);
                metadata.put("total_chunks", pieces.size());
                result.add(new Document(UUID.randomUUID().toString(), pieces.get(index), metadata, doc.getScore()));
            }
        }
        return result;
    }

    /**
     * 按 500 token 窗口与英文句末边界切分，保留原 trim 和短块过滤规则。
     * @param text 原始正文
     * @return 按原顺序排列的文本块
     */
    private List<String> splitText(String text) {
        List<String> pieces = new ArrayList<>();
        if (text.trim().isEmpty()) {
            return pieces;
        }
        List<Integer> tokens = encoding.encode(text).boxed();
        int offset = 0;
        while (offset < tokens.size()) {
            if (pieces.size() >= 100_000) {
                throw new IllegalStateException("文档切分块数达到上限，请拆分后上传");
            }
            int end = Math.min(offset + 500, tokens.size());
            String window = decode(tokens.subList(offset, end));
            if (window.trim().isEmpty()) {
                offset = end;
                continue;
            }
            if (end < tokens.size()) {
                int boundary = Math.max(Math.max(window.lastIndexOf('.'), window.lastIndexOf('?')),
                        Math.max(window.lastIndexOf('!'), window.lastIndexOf('\n')));
                if (boundary > 200) {
                    window = window.substring(0, boundary + 1);
                }
            }
            String piece = window.trim();
            if (piece.length() > 5) {
                pieces.add(piece);
            }
            int consumed = encoding.countTokens(window);
            if (consumed <= 0 || offset + consumed > tokens.size()) {
                throw new IllegalStateException("token 切分边界无法推进，请检查文档编码");
            }
            offset += consumed;
        }
        return pieces;
    }

    /**
     * 解码当前 token 窗口。
     * @param tokens 当前窗口的 token
     * @return 解码文本
     */
    private String decode(List<Integer> tokens) {
        IntArrayList values = new IntArrayList(tokens.size());
        tokens.forEach(values::add);
        return encoding.decode(values);
    }
}
