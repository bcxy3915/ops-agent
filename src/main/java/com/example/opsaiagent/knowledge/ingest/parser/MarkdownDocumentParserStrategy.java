package com.example.opsaiagent.knowledge.ingest.parser;

import org.springframework.ai.document.Document;
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

    @Override
    public List<Document> parse(Resource resource) {
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(false)
                .withIncludeCodeBlock(true)
                .withIncludeBlockquote(true)
                .build();
        return new MarkdownDocumentReader(resource, config).get();
    }
}