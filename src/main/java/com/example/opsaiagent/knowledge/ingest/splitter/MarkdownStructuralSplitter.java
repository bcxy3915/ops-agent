package com.example.opsaiagent.knowledge.ingest.splitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构化切分策略（order=10）。
 *
 * 自研 ATX 标题行扫描器（不依赖 MarkdownDocumentReader 的内部行为）：
 * - 按 H1~maxHeadingLevel 构建 headingPath（如「服务手册 > 数据库 > 主从切换」）；
 * - 每个 section 独立成块，超预算的 section 委托 RecursiveBoundarySplitter 切分（子块继承 headingPath）；
 * - 围栏代码块内部不识别标题（fence 状态跟踪），切分时由递归策略做原子保护；
 * - 无任何标题时整体交给 RecursiveBoundarySplitter，保证不失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkdownStructuralSplitter implements TextChunkStrategy {

    public static final String NAME = "markdown-structural";

    /** 章节路径 metadata key（检索侧 KnowledgeRetrievalService 已读该 key，零 DDL 复用） */
    public static final String METADATA_SECTION = "section";

    /** ATX 标题行：#~###### + 空格 + 标题文本 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    private final RecursiveBoundarySplitter recursiveSplitter;
    private final TokenMeter tokenMeter;
    private final SplitProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(SplitContext context) {
        return "MARKDOWN".equals(context.docType());
    }

    @Override
    public List<Document> split(List<Document> docs, SplitOptions options) {
        List<Document> chunks = new ArrayList<>();
        for (Document doc : docs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            for (Section section : scanSections(text)) {
                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                if (!section.headingPath().isBlank()) {
                    metadata.put(METADATA_SECTION, section.headingPath());
                }
                if (tokenMeter.count(section.text()) <= options.chunkSizeTokens()) {
                    chunks.add(new Document(section.text(), metadata));
                } else {
                    // 超长 section 委托递归切分，子块携带全部 metadata（含 section）
                    chunks.addAll(recursiveSplitter.split(List.of(new Document(section.text(), metadata)), options));
                }
            }
        }
        return chunks;
    }

    /**
     * 标题行扫描：按 ATX 标题切 section，维护标题栈生成 headingPath；
     * 围栏（``` / ~~~）内的 # 行不视为标题；首标题之前的内容 headingPath 为空。
     */
    List<Section> scanSections(String text) {
        List<Section> sections = new ArrayList<>();
        List<Heading> path = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentPath = "";
        boolean inFence = false;
        for (String line : RecursiveBoundarySplitter.splitKeepBreaks(text)) {
            String stripped = line.strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                inFence = !inFence;
                current.append(line);
                continue;
            }
            if (!inFence) {
                Matcher matcher = HEADING_PATTERN.matcher(stripped);
                if (matcher.matches()) {
                    if (!current.toString().isBlank()) {
                        sections.add(new Section(currentPath, current.toString()));
                    }
                    current = new StringBuilder();
                    currentPath = pushHeading(path, matcher.group(1).length(), matcher.group(2).trim());
                }
            }
            current.append(line);
        }
        if (!current.toString().isBlank()) {
            sections.add(new Section(currentPath, current.toString()));
        }
        return sections;
    }

    /** 标题栈进出栈：弹出同级及更深层级后压入；超过 maxHeadingLevel 的层级不压栈（仍作为 section 边界） */
    private String pushHeading(List<Heading> path, int level, String title) {
        while (!path.isEmpty() && path.get(path.size() - 1).level() >= level) {
            path.remove(path.size() - 1);
        }
        if (level <= properties.getMarkdown().getMaxHeadingLevel()) {
            path.add(new Heading(level, title));
        }
        StringBuilder sb = new StringBuilder();
        for (Heading heading : path) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(heading.title());
        }
        return sb.toString();
    }

    /** 章节：headingPath + 原文切片（含标题行） */
    record Section(String headingPath, String text) {
    }

    record Heading(int level, String title) {
    }
}
