package com.example.opsaiagent.knowledge.ingest.parser;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 纯文本（TXT）文档解析策略。
 *
 * 编码探测顺序：UTF-8 BOM → UTF-16 BOM → 严格 UTF-8 解码 → 失败回退 GB18030，
 * 避免 GBK/GB18030 编码的中文文档上传后出现乱码。
 */
@Component
public class TxtDocumentParserStrategy implements DocumentParserStrategy {

    /** GB18030 是 GBK 的超集，作为中文编码兜底 */
    private static final Charset FALLBACK_CHARSET = Charset.forName("GB18030");

    @Override
    public boolean support(String fileType) {
        return "TXT".equalsIgnoreCase(fileType);
    }

    @Override
    public List<Document> parse(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return List.of(new Document(decode(bytes)));
        } catch (IOException e) {
            throw new IllegalStateException("TXT 文档解析失败", e);
        }
    }

    private String decode(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // 严格 UTF-8 解码失败，按 GB18030 兜底
            return new String(bytes, FALLBACK_CHARSET);
        }
    }
}
