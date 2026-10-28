package com.example.opsaiagent.knowledge.ingest.parser;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PDF 文档解析策略。
 */
@Component
public class PdfDocumentParserStrategy implements DocumentParserStrategy {

    @Override
    public boolean support(String fileType) {
        return "PDF".equalsIgnoreCase(fileType);
    }

    @Override
    public List<Document> parse(Resource resource) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPageBottomMargin(0)
                .withPagesPerDocument(1)
                .build();
        return new PagePdfDocumentReader(resource, config).get();
    }
}