package com.example.opsaiagent.knowledge.ingest.splitter;

import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategyFactory;
import com.example.opsaiagent.knowledge.service.VectorIndexer;
import com.example.opsaiagent.retrieval.diversity.RetrievalDiversifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 新旧切分策略 A/B 评测 runner（设计文档 §10.3，上线决策依据）。
 *
 * 运行方式（surefire 默认不含 *IT，需显式指定）：
 * .\mvnw.cmd -Dtest=SplitEvalIT test
 *
 * 前置条件：
 * - 本地 PG + pgvector 就绪；
 * - 环境变量 DASHSCOPE_API_KEY 可用（走真实 text-embedding-v3，与生产同口径）；
 * - 评测数据集已放置：src/test/resources/splitter/eval/documents/（md/pdf/docx/txt 文档池）
 *   与 src/test/resources/splitter/eval/qa-golden.json（QA 评测集，格式见 §10.3.2）。
 *   数据集缺失时自动跳过，不影响常规测试。
 *
 * 评测流程：文档池双策略切分（基线 token=splitVersion 1 / 新策略=2，均打 evalDoc 标记）
 * → 逐 QA 双链路 top-10 检索 → Recall@5 / ChunkHit@5 / MRR@10 / 重复命中 / token 增量
 * → §10.3.6 gate 判定 → 输出报告至 docs/design/text-split/eval/评测报告-YYYYMMDD.md
 * → 清理全部 evalDoc 向量。
 *
 * 注意：两条链路仅切分策略不同（§10.3.1 单一变量原则），
 * embedding 文本不携带「标题｜章节」上下文头（该增益属于后续迭代，另行评测）。
 */
@SpringBootTest
@DisplayName("文本切分 A/B 评测")
class SplitEvalIT {

    private static final Path DATASET_DIR = Paths.get("src/test/resources/splitter/eval");
    private static final Path DOCUMENTS_DIR = DATASET_DIR.resolve("documents");
    private static final Path QA_FILE = DATASET_DIR.resolve("qa-golden.json");
    private static final Path REPORT_DIR = Paths.get("docs/design/text-split/eval");
    private static final String CLEANUP_SQL = "DELETE FROM vector_store WHERE metadata->>'evalDoc' = 'true'";

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private VectorIndexer vectorIndexer;
    @Autowired
    private TextChunkStrategyResolver strategyResolver;
    @Autowired
    private DocumentParserStrategyFactory parserFactory;
    @Autowired
    private SplitProperties splitProperties;
    @Autowired
    private TokenMeter tokenMeter;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RetrievalDiversifier diversifier;

    @Value("${spring.ai.openai.embedding.model:text-embedding-v3}")
    private String embeddingModel;

    /** QA 评测条目（qa-golden.json） */
    private record EvalQuestion(String id, String docType, String query, String expectedDocId,
                                List<String> expectedKeywords, boolean boundaryCase) {
    }

    /** 单条 QA 在单条链路上的判定结果 */
    private record ArmResult(boolean docHit5, boolean chunkHit5, double mrr10, boolean dupHit) {
    }

    /** 单条 QA 双链路判定（供逐题明细） */
    private record PairResult(EvalQuestion question, ArmResult baseline, ArmResult fresh) {
    }

    /** 分类型聚合指标 */
    private static final class TypeStats {
        int questions;
        int docHitBase;
        int docHitNew;
        int chunkHitBase;
        int chunkHitNew;
        double mrrBase;
        double mrrNew;
        int dupBase;
        int dupNew;
    }

    /** 分类型入库统计（成本观察项） */
    private static final class DocStats {
        int docs;
        int chunksBase;
        int chunksNew;
        long tokensBase;
        long tokensNew;
    }

    @BeforeAll
    static void requireDataset() {
        Assumptions.assumeTrue(Files.isDirectory(DOCUMENTS_DIR),
                "评测数据集未准备（" + DOCUMENTS_DIR + "），跳过 A/B 评测");
        Assumptions.assumeTrue(Files.isRegularFile(QA_FILE),
                "缺少 QA 评测集（" + QA_FILE + "），跳过 A/B 评测");
        Assumptions.assumeTrue(
                StringUtils.hasText(System.getenv("DASHSCOPE_API_KEY")),
                "未配置环境变量 DASHSCOPE_API_KEY（真实 embedding 前置），跳过 A/B 评测");
    }

    @AfterEach
    void cleanupEvalVectors() {
        if (jdbcTemplate != null) {
            int deleted = jdbcTemplate.update(CLEANUP_SQL);
            // 评测数据按 evalDoc 标记清理，绝不触碰生产向量
            System.out.println("[SplitEvalIT] 已清理评测向量 " + deleted + " 条");
        }
    }

    @Test
    @DisplayName("双链路评测：切分入库 → 双臂检索 → 指标计算 → gate 判定 → 报告输出")
    void runEval() throws IOException {
        // 0. 清理历史评测数据，保证可重复运行
        jdbcTemplate.update(CLEANUP_SQL);

        // 1. 加载文档池与 QA 评测集
        List<Path> files = listDocuments();
        List<EvalQuestion> questions = loadQuestions();
        Map<String, String> docTypes = new LinkedHashMap<>();
        Map<String, DocStats> docStats = new TreeMap<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            docTypes.put(fileName, DocumentParserStrategyFactory.resolveDocType(fileName));
            docStats.computeIfAbsent(DocumentParserStrategyFactory.resolveDocType(fileName),
                    k -> new DocStats()).docs++;
        }

        // 2. 双策略切分入库（仅切分策略不同，基线 splitVersion=1 / 新策略=2）
        // §10.3.11 上下文头增益评测：-Dsplit.eval.contextHeader 时双臂 embedding 文本同口径
        // 加「【标题｜章节】」头（与生产 KnowledgeDocumentIndexer.embeddingText 同格式），单一变量 = 头的有无
        boolean contextHeader = System.getProperty("split.eval.contextHeader") != null;
        List<Document> baselineAll = new ArrayList<>();
        List<Document> freshAll = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String docType = DocumentParserStrategyFactory.resolveDocType(fileName);
            List<Document> docs = parseDocument(file, docType);

            List<Document> baseline = strategyResolver.split(docs, new SplitContext(
                    docType, fileName, stripExtension(fileName), null, null, TokenFallbackSplitter.NAME));
            List<Document> fresh = strategyResolver.split(docs, new SplitContext(
                    docType, fileName, stripExtension(fileName), null, null, null));

            DocStats stats = docStats.get(docType);
            tagEvalMetadata(baseline, fileName, docType, 1);
            tagEvalMetadata(fresh, fileName, docType, 2);
            if (contextHeader) {
                baseline = applyContextHeader(baseline, stripExtension(fileName));
                fresh = applyContextHeader(fresh, stripExtension(fileName));
            }
            stats.chunksBase += baseline.size();
            stats.chunksNew += fresh.size();
            stats.tokensBase += totalTokens(baseline);
            stats.tokensNew += totalTokens(fresh);
            baselineAll.addAll(baseline);
            freshAll.addAll(fresh);
        }
        vectorIndexer.write(baselineAll);
        vectorIndexer.write(freshAll);
        System.out.println("[SplitEvalIT] 双链路入库完成: 基线 " + baselineAll.size()
                + " 块 / 新策略 " + freshAll.size() + " 块");

        // 3. 逐 QA 双臂检索（top-10：top-5 判 Recall/ChunkHit，top-10 判 MRR）
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        Filter.Expression baseFilter = fb.and(fb.eq("evalDoc", "true"), fb.eq("splitVersion", 1)).build();
        Filter.Expression freshFilter = fb.and(fb.eq("evalDoc", "true"), fb.eq("splitVersion", 2)).build();

        Map<String, TypeStats> typeStats = new TreeMap<>();
        List<PairResult> details = new ArrayList<>();
        // §10.3.8 人工抽检支撑：-Dsplit.eval.dumpTop1 时导出每题双臂 top-1 块原文
        boolean dumpTop1 = System.getProperty("split.eval.dumpTop1") != null;
        StringBuilder top1Dump = new StringBuilder();
        // §10.3.10 检索层治理验证：-Dsplit.eval.diversify 时双臂 top-10 先过同文档治理再判定，
        // 模拟生产 HybridRetriever 的 diversity 行为（两臂同口径，单一变量不变）
        boolean diversifyMode = System.getProperty("split.eval.diversify") != null;
        for (EvalQuestion question : questions) {
            if (!docTypes.containsKey(question.expectedDocId())) {
                System.out.println("[SplitEvalIT] 警告: QA " + question.id()
                        + " 的 expectedDocId=" + question.expectedDocId() + " 不在文档池中，跳过");
                continue;
            }
            List<Document> baseTop = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question.query()).topK(10).filterExpression(baseFilter).build());
            List<Document> freshTop = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question.query()).topK(10).filterExpression(freshFilter).build());
            if (diversifyMode) {
                baseTop = diversifier.diversify(baseTop, 10);
                freshTop = diversifier.diversify(freshTop, 10);
            }

            ArmResult baseResult = judge(baseTop, question);
            ArmResult freshResult = judge(freshTop, question);
            details.add(new PairResult(question, baseResult, freshResult));

            if (dumpTop1) {
                top1Dump.append("## ").append(question.id()).append(" (").append(question.docType())
                        .append(question.boundaryCase() ? "/边界" : "").append(") 期望: ").append(question.expectedDocId())
                        .append("\n\n> ").append(question.query()).append("\n\n");
                appendTop1(top1Dump, "基线", baseTop);
                appendTop1(top1Dump, "新策略", freshTop);
                top1Dump.append("\n---\n\n");
            }

            TypeStats stats = typeStats.computeIfAbsent(question.docType(), k -> new TypeStats());
            stats.questions++;
            stats.docHitBase += baseResult.docHit5() ? 1 : 0;
            stats.docHitNew += freshResult.docHit5() ? 1 : 0;
            stats.chunkHitBase += baseResult.chunkHit5() ? 1 : 0;
            stats.chunkHitNew += freshResult.chunkHit5() ? 1 : 0;
            stats.mrrBase += baseResult.mrr10();
            stats.mrrNew += freshResult.mrr10();
            stats.dupBase += baseResult.dupHit() ? 1 : 0;
            stats.dupNew += freshResult.dupHit() ? 1 : 0;
        }

        // 4. gate 判定（§10.3.6）与报告输出（§10.3.4）；diversify/header 模式独立命名，不覆盖常规评测终稿
        String decision = evaluateGate(details, typeStats, docStats);
        String report = buildReport(questions, details, typeStats, docStats, decision, diversifyMode, contextHeader);
        String fileSuffix = (diversifyMode ? "-diversity" : "") + (contextHeader ? "-header" : "");
        Path reportFile = REPORT_DIR.resolve("评测报告-"
                + LocalDate.now().toString().replace("-", "") + fileSuffix + ".md");
        Files.createDirectories(REPORT_DIR);
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        System.out.println("[SplitEvalIT] 评测完成，决策: " + decision);
        System.out.println("[SplitEvalIT] 报告已输出: " + reportFile.toAbsolutePath());
        if (dumpTop1) {
            Path dumpFile = REPORT_DIR.resolve("抽检导出-"
                    + LocalDate.now().toString().replace("-", "") + fileSuffix + ".md");
            Files.writeString(dumpFile, top1Dump.toString(), StandardCharsets.UTF_8);
            System.out.println("[SplitEvalIT] 抽检导出已输出: " + dumpFile.toAbsolutePath());
        }
    }

    /** 抽检导出：单臂 top-1 块的归属与原文 */
    private void appendTop1(StringBuilder sb, String armLabel, List<Document> top) {
        if (top == null || top.isEmpty()) {
            sb.append("**").append(armLabel).append(" top-1**:（无结果）\n\n");
            return;
        }
        Document first = top.get(0);
        sb.append("**").append(armLabel).append(" top-1** [").append(first.getMetadata().get("docId"))
                .append(" #").append(first.getMetadata().get("ordinal")).append("]\n\n")
                .append(first.getText().replace("\n", " ⏎ "))
                .append("\n\n");
    }

    // ==================== 数据加载 ====================

    private List<Path> listDocuments() throws IOException {
        try (Stream<Path> stream = Files.list(DOCUMENTS_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> DocumentParserStrategyFactory.resolveDocType(f.getFileName().toString()) != null)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private List<EvalQuestion> loadQuestions() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(QA_FILE.toFile());
        List<EvalQuestion> questions = new ArrayList<>();
        for (JsonNode node : root) {
            List<String> keywords = new ArrayList<>();
            node.path("expectedKeywords").forEach(k -> keywords.add(k.asText()));
            questions.add(new EvalQuestion(
                    node.path("id").asText(),
                    node.path("docType").asText(),
                    node.path("query").asText(),
                    node.path("expectedDocId").asText(),
                    keywords,
                    node.path("boundaryCase").asBoolean(false)));
        }
        return questions;
    }

    private List<Document> parseDocument(Path file, String docType) throws IOException {
        if ("MARKDOWN".equals(docType)) {
            // 与 DocumentLoader 同口径：Markdown 直读原文，交给 markdown-structural 切分
            return List.of(new Document(Files.readString(file, StandardCharsets.UTF_8)));
        }
        return parserFactory.getStrategy(docType).parse(new FileSystemResource(file));
    }

    // ==================== 入库打标 ====================

    /** 为评测块打隔离标记：evalDoc（清理标记）/ evalArm（链路）/ docId / ordinal（重复命中判定） */
    private void tagEvalMetadata(List<Document> chunks, String fileName, String docType, int arm) {
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = chunks.get(i).getMetadata();
            metadata.put("evalDoc", "true");
            metadata.put("evalArm", arm);
            metadata.put("splitVersion", arm); // 基线=1 / 新策略=2，与检索过滤一致
            metadata.put("docId", fileName);
            metadata.put("docType", docType);
            metadata.put("ordinal", i);
            metadata.put("source", "eval:" + fileName);
        }
    }

    /**
     * §10.3.11 上下文头：与生产 KnowledgeDocumentIndexer.embeddingText 同格式，
     * embedding 文本前置「【标题｜章节】」头（两者均缺失时不加头，仅有标题时「【标题】」）。
     * Document text 不可变，故重建 Document 并复制 metadata。
     */
    private List<Document> applyContextHeader(List<Document> chunks, String title) {
        List<Document> result = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            String section = chunk.getMetadata().get(MarkdownStructuralSplitter.METADATA_SECTION) instanceof String s
                    ? s : null;
            boolean hasTitle = StringUtils.hasText(title);
            boolean hasSection = StringUtils.hasText(section);
            if (!hasTitle && !hasSection) {
                result.add(chunk);
                continue;
            }
            String header = hasTitle && hasSection ? title + "｜" + section : (hasTitle ? title : section);
            result.add(new Document("【" + header + "】\n" + chunk.getText(),
                    new LinkedHashMap<>(chunk.getMetadata())));
        }
        return result;
    }

    private long totalTokens(List<Document> chunks) {
        return chunks.stream().mapToLong(c -> tokenMeter.count(c.getText())).sum();
    }

    // ==================== 检索判定 ====================

    /** doc 级命中（top-5 出现 expectedDocId）/ chunk 级命中（top-5 该 doc 块含任一关键词）/ MRR@10 / 相邻块重复命中 */
    private ArmResult judge(List<Document> top, EvalQuestion question) {
        boolean docHit5 = false;
        boolean chunkHit5 = false;
        double mrr10 = 0.0;
        for (int i = 0; i < top.size(); i++) {
            if (!question.expectedDocId().equals(top.get(i).getMetadata().get("docId"))) {
                continue;
            }
            if (i < 5) {
                docHit5 = true;
                String text = top.get(i).getText();
                if (question.expectedKeywords().stream().anyMatch(text::contains)) {
                    chunkHit5 = true;
                }
            }
            if (mrr10 == 0.0) {
                mrr10 = 1.0 / (i + 1);
            }
        }
        return new ArmResult(docHit5, chunkHit5, mrr10, hasAdjacentDuplicate(top));
    }

    /** top-5 中同 docId 且 ordinal 相邻的块同时出现（重复感观察项） */
    private boolean hasAdjacentDuplicate(List<Document> top) {
        int limit = Math.min(5, top.size());
        for (int i = 0; i < limit; i++) {
            for (int j = i + 1; j < limit; j++) {
                if (top.get(i).getMetadata().get("docId") != null
                        && top.get(i).getMetadata().get("docId").equals(top.get(j).getMetadata().get("docId"))
                        && top.get(i).getMetadata().get("ordinal") instanceof Number o1
                        && top.get(j).getMetadata().get("ordinal") instanceof Number o2
                        && Math.abs(o1.intValue() - o2.intValue()) == 1) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== gate 判定（§10.3.6 / §10.3.7） ====================

    private String evaluateGate(List<PairResult> details, Map<String, TypeStats> typeStats,
                                Map<String, DocStats> docStats) {
        TypeStats overall = aggregate(typeStats);
        double recallDeltaPp = ratio(overall.docHitNew, overall.questions) * 100
                - ratio(overall.docHitBase, overall.questions) * 100;
        double chunkDeltaPp = ratio(overall.chunkHitNew, overall.questions) * 100
                - ratio(overall.chunkHitBase, overall.questions) * 100;

        boolean mainPass;
        if (recallDeltaPp >= 10) {
            mainPass = chunkDeltaPp > 0;
        } else if (recallDeltaPp >= 5) {
            // 5~10pp：McNemar 思路配对分析，正差 : 负差 ≥ 2:1
            long positive = details.stream().filter(d -> d.fresh().docHit5() && !d.baseline().docHit5()).count();
            long negative = details.stream().filter(d -> !d.fresh().docHit5() && d.baseline().docHit5()).count();
            mainPass = positive >= 2 * negative;
        } else {
            mainPass = false;
        }

        boolean noTypeRegression = typeStats.values().stream()
                .allMatch(s -> (ratio(s.docHitNew, s.questions) - ratio(s.docHitBase, s.questions)) * 100 >= -3.0);

        long tokensBase = docStats.values().stream().mapToLong(s -> s.tokensBase).sum();
        long tokensNew = docStats.values().stream().mapToLong(s -> s.tokensNew).sum();
        boolean costOk = tokensBase == 0 || (tokensNew - tokensBase) * 100.0 / tokensBase <= 15.0;

        if (mainPass && noTypeRegression) {
            return costOk
                    ? "显著提升：新策略默认启用（保持 default-strategy: recursive + markdown.strategy: markdown-structural）"
                    : "召回显著提升但 token 增量超 15%：新策略启用，但需下调 chunkSize/overlap 调参复测成本";
        }
        if (mainPass) {
            return "部分类型提升：按类型路由，仅提升的类型启用新策略（docType 覆盖配置），劣化类型保持 token";
        }
        return "提升不显著：回退 default-strategy: token，框架与评测集保留，调参后重测";
    }

    private TypeStats aggregate(Map<String, TypeStats> typeStats) {
        TypeStats overall = new TypeStats();
        for (TypeStats stats : typeStats.values()) {
            overall.questions += stats.questions;
            overall.docHitBase += stats.docHitBase;
            overall.docHitNew += stats.docHitNew;
            overall.chunkHitBase += stats.chunkHitBase;
            overall.chunkHitNew += stats.chunkHitNew;
            overall.mrrBase += stats.mrrBase;
            overall.mrrNew += stats.mrrNew;
            overall.dupBase += stats.dupBase;
            overall.dupNew += stats.dupNew;
        }
        return overall;
    }

    private double ratio(int hit, int total) {
        return total == 0 ? 0.0 : (double) hit / total;
    }

    // ==================== 报告生成（§10.3.4 / §10.3.8） ====================

    private String buildReport(List<EvalQuestion> questions, List<PairResult> details,
                               Map<String, TypeStats> typeStats, Map<String, DocStats> docStats,
                               String decision, boolean diversifyMode, boolean contextHeader) {
        TypeStats overall = aggregate(typeStats);
        StringBuilder sb = new StringBuilder();
        sb.append("# 文本切分策略 A/B 评测报告\n\n");
        sb.append("- 运行日期：").append(LocalDate.now()).append('\n');
        sb.append("- embedding 模型：").append(embeddingModel).append("（真实 API，与生产同口径）\n");
        sb.append("- 切分配置：chunkSize=").append(splitProperties.getChunkSizeTokens())
                .append(" / overlap=").append(splitProperties.getOverlapTokens())
                .append(" / minChunkChars=").append(splitProperties.getMinChunkChars())
                .append(" / maxChunkTokens=").append(splitProperties.getMaxChunkTokens())
                .append(" / default=").append(splitProperties.getDefaultStrategy())
                .append(" / markdown=").append(splitProperties.getMarkdown().getStrategy())
                .append("(maxHeadingLevel=").append(splitProperties.getMarkdown().getMaxHeadingLevel()).append(")\n");
        sb.append("- 数据集：文档 ").append(docStats.values().stream().mapToInt(s -> s.docs).sum())
                .append(" 篇 / QA ").append(details.size()).append(" 条（边界类 ")
                .append(details.stream().filter(d -> d.question().boundaryCase()).count()).append(" 条）\n");
        sb.append("- 单一变量说明：两链路仅切分策略不同；embedding 文本")
                .append(contextHeader ? "双臂同口径携带「标题｜章节」上下文头" : "不含「标题｜章节」上下文头").append('\n');
        if (diversifyMode) {
            sb.append("- 检索层治理（§10.3.10）：本报告为 diversify 模式——双臂 top-10 均先过同文档治理")
                    .append("（max-per-doc 配额 + 邻近合并）再判定，模拟生产 HybridRetriever 行为\n");
        }
        if (contextHeader) {
            sb.append("- 上下文头（§10.3.11）：本报告为 contextHeader 模式——双臂 embedding 文本同口径前置")
                    .append("「【标题｜章节】」头（与生产 KnowledgeDocumentIndexer.embeddingText 同格式），")
                    .append("对比基线为 diversify 模式报告，单一变量 = 头的有无\n");
        }
        sb.append('\n');

        sb.append("## 分类型指标\n\n");
        sb.append("| docType | QA 数 | Recall@5 基线→新 | ChunkHit@5 基线→新 | MRR@10 基线→新 | 重复命中 基线→新 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Map.Entry<String, TypeStats> entry : typeStats.entrySet()) {
            TypeStats s = entry.getValue();
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(s.questions)
                    .append(" | ").append(pct(s.docHitBase, s.questions)).append(" → ").append(pct(s.docHitNew, s.questions))
                    .append(" | ").append(pct(s.chunkHitBase, s.questions)).append(" → ").append(pct(s.chunkHitNew, s.questions))
                    .append(" | ").append(String.format("%.3f → %.3f", s.mrrBase / Math.max(s.questions, 1), s.mrrNew / Math.max(s.questions, 1)))
                    .append(" | ").append(s.dupBase).append(" → ").append(s.dupNew).append(" |\n");
        }
        sb.append("| **总体** | ").append(overall.questions)
                .append(" | ").append(pct(overall.docHitBase, overall.questions)).append(" → ").append(pct(overall.docHitNew, overall.questions))
                .append(" | ").append(pct(overall.chunkHitBase, overall.questions)).append(" → ").append(pct(overall.chunkHitNew, overall.questions))
                .append(" | ").append(String.format("%.3f → %.3f", overall.mrrBase / Math.max(overall.questions, 1), overall.mrrNew / Math.max(overall.questions, 1)))
                .append(" | ").append(overall.dupBase).append(" → ").append(overall.dupNew).append(" |\n\n");

        sb.append("## 成本观察项\n\n");
        sb.append("| docType | 文档数 | 块数 基线→新 | token 总量 基线→新 | token 增量 |\n|---|---|---|---|---|\n");
        for (Map.Entry<String, DocStats> entry : docStats.entrySet()) {
            DocStats s = entry.getValue();
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(s.docs)
                    .append(" | ").append(s.chunksBase).append(" → ").append(s.chunksNew)
                    .append(" | ").append(s.tokensBase).append(" → ").append(s.tokensNew)
                    .append(" | ").append(s.tokensBase == 0 ? "-" : String.format("%.1f%%",
                            (s.tokensNew - s.tokensBase) * 100.0 / s.tokensBase)).append(" |\n");
        }
        sb.append('\n');

        sb.append("## Gate 判定（§10.3.6）\n\n> ").append(decision).append("\n\n");

        sb.append("## 逐题明细\n\n");
        sb.append("| QA | docType | 边界类 | doc命中 基→新 | chunk命中 基→新 | MRR 基→新 |\n|---|---|---|---|---|---|\n");
        for (PairResult detail : details) {
            sb.append("| ").append(detail.question().id())
                    .append(" | ").append(detail.question().docType())
                    .append(" | ").append(detail.question().boundaryCase() ? "是" : "否")
                    .append(" | ").append(mark(detail.baseline().docHit5())).append("→").append(mark(detail.fresh().docHit5()))
                    .append(" | ").append(mark(detail.baseline().chunkHit5())).append("→").append(mark(detail.fresh().chunkHit5()))
                    .append(" | ").append(String.format("%.2f→%.2f", detail.baseline().mrr10(), detail.fresh().mrr10()))
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## 人工抽检清单（§10.3.8，报告必填）\n\n");
        sb.append("- [ ] 每类型抽 5 条 QA，并排粘贴新旧两链路 top-1 块原文，标注「完整 / 断裂 / 跑题」\n");
        sb.append("- [ ] boundaryCase 题目专项抽检：答案是否可从单个块内完整获得（含前提条件）\n");
        sb.append("- [ ] 抽检结论量化（边界断裂率 = 断裂块数 / 抽检块数，目标较基线下降 ≥ 50%）\n");
        return sb.toString();
    }

    private String pct(int hit, int total) {
        return String.format("%.1f%%", ratio(hit, total) * 100);
    }

    private String mark(boolean hit) {
        return hit ? "✓" : "✗";
    }

    private String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }
}
