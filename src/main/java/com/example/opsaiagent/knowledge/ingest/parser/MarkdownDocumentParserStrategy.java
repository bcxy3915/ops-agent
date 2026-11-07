package com.example.opsaiagent.knowledge.ingest.parser;

import com.example.opsaiagent.ai.adapter.springai.SpringAiDocumentMapper;

import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Markdown 文档解析策略。
 */
@Component
public class MarkdownDocumentParserStrategy implements DocumentParserStrategy {

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
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(false)
                .withIncludeCodeBlock(true)
                .withIncludeBlockquote(true)
                .build();
        return SpringAiDocumentMapper.fromSpring(new MarkdownDocumentReader(resource, config).get());
    }
}
