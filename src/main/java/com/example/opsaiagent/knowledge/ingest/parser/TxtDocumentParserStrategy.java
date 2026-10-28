package com.example.opsaiagent.knowledge.ingest.parser;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 纯文本（TXT）文档解析策略。
 */
@Component
public class TxtDocumentParserStrategy implements DocumentParserStrategy {

    @Override
    public boolean support(String fileType) {
        return "TXT".equalsIgnoreCase(fileType);
    }

    @Override
    public List<Document> parse(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return List.of(new Document(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("TXT 文档解析失败", e);
        }
    }
}