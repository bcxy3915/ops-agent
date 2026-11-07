package com.example.opsaiagent.knowledge.ingest.parser;

import com.example.opsaiagent.knowledge.model.Document;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 文档解析策略，直接解析 CommonMark 语法树。
 * 分组兼容规则及 Apache-2.0 来源见 docs/licenses/spring-ai-compatibility.md。
 */
@Component
public class MarkdownDocumentParserStrategy implements DocumentParserStrategy {

    /**
     * 判断是否处理 Markdown。
     * @param fileType 文档类型
     * @return 是否匹配
     */
    @Override
    public boolean support(String fileType) {
        return "MARKDOWN".equalsIgnoreCase(fileType);
    }

    /**
     * 抽取 Markdown 正文并转换为项目文档。
     * @param resource 待解析资源
     * @return 保留代码块、引用和元数据的文档
     */
    @Override
    public List<Document> parse(Resource resource) {
        try (var input = resource.getInputStream();
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Extraction extraction = new Extraction();
            extraction.walk(Parser.builder().build().parseReader(reader));
            extraction.flush();
            return extraction.documents;
        } catch (IOException e) {
            throw new IllegalStateException("Markdown 文档解析失败", e);
        }
    }

    /** 每次解析独立持有状态，避免并发上传相互污染。 */
    private static final class Extraction {
        private final List<Document> documents = new ArrayList<>();
        private final StringBuilder content = new StringBuilder();
        private Map<String, Object> metadata = new HashMap<>();

        /**
         * 按语法树顺序抽取文本，保留标题分组与代码分类。
         * @param node 当前语法节点
         * @return 无返回值
         */
        private void walk(Node node) {
            if (node instanceof Heading) {
                flush();
            } else if (node instanceof Text text) {
                if (node.getParent() instanceof Heading heading) {
                    metadata.put("category", "header_" + heading.getLevel());
                    metadata.put("title", text.getLiteral());
                } else {
                    content.append(text.getLiteral());
                }
            } else if (node instanceof Code code) {
                content.append(code.getLiteral());
                metadata.put("category", "code_inline");
            } else if (node instanceof FencedCodeBlock code) {
                space();
                content.append(code.getLiteral());
                metadata.put("category", "code_block");
                metadata.put("lang", code.getInfo());
                flush();
            } else if (node instanceof BlockQuote) {
                space();
                metadata.put("category", "blockquote");
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak || node instanceof ListItem) {
                space();
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                walk(child);
            }
        }

        /**
         * 将显式换行和列表边界转为空格。
         * @return 无返回值
         */
        private void space() {
            if (!content.isEmpty()) {
                content.append(' ');
            }
        }

        /**
         * 输出当前分组并清理状态。
         * @return 无返回值
         */
        private void flush() {
            if (!content.isEmpty()) {
                documents.add(new Document(content.toString(), metadata));
                content.setLength(0);
            }
            metadata = new HashMap<>();
        }
    }
}
