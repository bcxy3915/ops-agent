package com.example.opsaiagent.retrieval.diversity;

import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索结果同文档竞争治理（设计文档 §12 风险 #2 对策 / §10.3.10）。
 *
 * 背景：embedding 排序下，同主题的高相似文档（或同一文档的多个块）容易霸占 top-K，
 * 把期望文档的关键块挤到窗口之外（评测 Q013/Q068 的 chunk miss 根因）。
 *
 * 两个机制（均按 rank 顺序贪心执行，不改变 top-1）：
 * 1. 同文档配额（max-per-doc）：top-K 内同一 docId 最多保留 N 块，超额块让位给后排文档；
 * 2. 邻近合并（merge-adjacent）：同文档且 ordinal 相邻的块并入已保留块（文本拼接），
 *    不额外占用 top-K 名额——overlap 切分下相邻块本就内容连续，合并既去重又保上下文；
 *    每个保留位最多吸收 1 块，防止链式合并产生超长块。
 *
 * 回退：ops-agent.retrieval.diversity.enabled=false 即恢复原始排序。
 */
@Slf4j
@Component
public class RetrievalDiversifier {

    @Value("${ops-agent.retrieval.diversity.enabled:true}")
    private boolean enabled;

    /** top-K 内单文档最大块数 */
    @Value("${ops-agent.retrieval.diversity.max-per-doc:2}")
    private int maxPerDoc;

    /** 同文档 ordinal 相邻块合并为一个结果 */
    @Value("${ops-agent.retrieval.diversity.merge-adjacent:true}")
    private boolean mergeAdjacent;

    /** 保留位：已选块及其同文档计数状态 */
    private static final class Slot {
        Document doc;
        int ordinal;
        boolean absorbed;
        String docKey;
    }

    /**
     * 对排序后的候选列表做同文档多样性治理。
     *
     * @param ranked 按相关性排序的候选（如 RRF 融合结果）
     * @param limit  最多保留的结果数
     * @return 治理后的结果列表（顺序保持原 rank 相对序，top-1 不变）
     */
    public List<Document> diversify(List<Document> ranked, int limit) {
        if (ranked == null || ranked.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (!enabled) {
            return ranked.stream().limit(limit).toList();
        }
        int cap = Math.max(1, maxPerDoc);

        List<Slot> slots = new ArrayList<>();
        Map<String, Integer> docCount = new HashMap<>();
        for (Document candidate : ranked) {
            if (slots.size() >= limit) {
                break;
            }
            String docKey = docKey(candidate);
            int ordinal = ordinal(candidate);

            // 1. 邻近合并：同文档且 ordinal 相邻 → 并入已保留位，不占新名额
            if (mergeAdjacent) {
                Slot adjacent = findAdjacentSlot(slots, docKey, ordinal);
                if (adjacent != null) {
                    adjacent.doc = merge(adjacent.doc, candidate);
                    adjacent.absorbed = true;
                    continue;
                }
            }

            // 2. 同文档配额：超额块跳过，让位给后排的其他文档
            int count = docCount.getOrDefault(docKey, 0);
            if (count >= cap) {
                continue;
            }
            Slot slot = new Slot();
            slot.doc = candidate;
            slot.ordinal = ordinal;
            slot.docKey = docKey;
            slots.add(slot);
            docCount.merge(docKey, 1, Integer::sum);
        }

        List<Document> result = slots.stream().map(s -> s.doc).toList();
        if (result.size() < Math.min(limit, ranked.size())) {
            log.debug("同文档多样性治理: 候选 {} → 保留 {}（max-per-doc={}）",
                    ranked.size(), result.size(), cap);
        }
        return result;
    }

    /** 查找可吸收该候选的保留位：同文档、ordinal 相邻、且尚未吸收过 */
    private Slot findAdjacentSlot(List<Slot> slots, String docKey, int ordinal) {
        if (ordinal < 0) {
            return null;
        }
        for (Slot slot : slots) {
            if (!slot.absorbed && slot.docKey.equals(docKey)
                    && slot.ordinal >= 0 && Math.abs(slot.ordinal - ordinal) == 1) {
                return slot;
            }
        }
        return null;
    }

    /** 合并相邻块：文本拼接，元数据沿用先命中（rank 更高）的块 */
    private Document merge(Document kept, Document absorbed) {
        Map<String, Object> metadata = kept.getMetadata() == null ? Map.of() : kept.getMetadata();
        String text = kept.getText() == null ? "" : kept.getText();
        String extra = absorbed.getText() == null ? "" : absorbed.getText();
        if (extra.isBlank() || extra.equals(text)) {
            return kept;
        }
        return new Document(text + "\n" + extra, metadata);
    }

    /** 文档分组键：docId → source → 按块 id 独立（无归属元数据时不做配额/合并） */
    private String docKey(Document doc) {
        Map<String, Object> md = doc.getMetadata();
        if (md != null) {
            String docId = str(md.get("docId"));
            if (StringUtils.hasText(docId)) {
                return "doc:" + docId;
            }
            String source = str(md.get("source"));
            if (StringUtils.hasText(source)) {
                return "src:" + source;
            }
        }
        return "id:" + doc.getId();
    }

    /** ordinal 解析：兼容生产（String）与评测（Integer）两种写入口径 */
    private int ordinal(Document doc) {
        Map<String, Object> md = doc.getMetadata();
        if (md == null) {
            return -1;
        }
        Object value = md.get("ordinal");
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // 非数字 ordinal 视为不可合并
            }
        }
        return -1;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
