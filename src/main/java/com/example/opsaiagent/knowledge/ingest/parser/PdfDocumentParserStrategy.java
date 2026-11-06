package com.example.opsaiagent.knowledge.ingest.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 文档解析策略。
 *
 * 不直接使用 PagePdfDocumentReader：其实现将整份 PDF 载入堆内存，大文件下内存峰值过高。
 * 此处先将输入流落地为临时文件，再由 PDFBox 基于文件按需读取（RandomAccessRead），
 * 逐页抽取文本、逐页产出 Document，峰值内存与文件大小基本解耦。
 */
@Component
public class PdfDocumentParserStrategy implements DocumentParserStrategy {

    @Override
    public boolean support(String fileType) {
        return "PDF".equalsIgnoreCase(fileType);
    }

    @Override
    public List<Document> parse(Resource resource) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("pdf-parse-", ".pdf");
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            List<Document> docs = new ArrayList<>();
            try (PDDocument pdf = Loader.loadPDF(tempFile.toFile())) {
                int pages = pdf.getNumberOfPages();
                PDFTextStripper stripper = new PDFTextStripper();
                for (int i = 1; i <= pages; i++) {
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    String text = stripper.getText(pdf);
                    if (StringUtils.hasText(text)) {
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("page_number", i);
                        docs.add(new Document(text.trim(), metadata));
                    }
                }
            }
            return docs;
        } catch (IOException e) {
            throw new IllegalStateException("PDF 文档解析失败: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响解析结果
                }
            }
        }
    }
}
