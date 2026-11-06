package com.example.opsaiagent.knowledge.ingest.parser;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * DOCX 文档解析策略（抽取段落文本）。
 */
@Component
public class DocxDocumentParserStrategy implements DocumentParserStrategy {

    static {
        // zip bomb 防护：压缩率低于 0.5% 判定为恶意文件直接拒绝（POI 默认 1%，此处收紧）
        ZipSecureFile.setMinInflateRatio(0.005d);
    }

    @Override
    public boolean support(String fileType) {
        return "DOCX".equalsIgnoreCase(fileType);
    }

    @Override
    public List<Document> parse(Resource resource) {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(resource.getInputStream())) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("DOCX 文档解析失败", e);
        }
        return List.of(new Document(sb.toString()));
    }
}