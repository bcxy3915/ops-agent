package com.example.opsaiagent.knowledge.ingest.parser;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文档解析策略工厂类
 */
@Component
@RequiredArgsConstructor
public class DocumentParserStrategyFactory {

    /** 文件扩展名 → 文档类型的唯一映射源（上传/内置加载共用） */
    private static final Map<String, String> EXTENSION_TO_DOC_TYPE = Map.of(
            "md", "MARKDOWN",
            "markdown", "MARKDOWN",
            "pdf", "PDF",
            "txt", "TXT",
            "docx", "DOCX");

    private final List<DocumentParserStrategy> strategies;

    /**
     * 根据文件名解析文档类型
     * @param fileName 文件名（含扩展名）
     * @return 文档类型；不支持的扩展名返回 null
     */
    public static String resolveDocType(String fileName) {
        if (fileName == null) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return null;
        }
        return EXTENSION_TO_DOC_TYPE.get(fileName.substring(idx + 1).toLowerCase());
    }

    /**
     * 根据文件类型获取解析策略
     * @param fileType 文件类型
     * @return 解析策略
     */
    public DocumentParserStrategy getStrategy(String fileType) {
        return strategies.stream()
                .filter(strategy -> strategy.support(fileType))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SUPPORTED_FILE_TYPE));
    }

    /**
     * 判断文件类型是否被支持
     * @param fileType 文件类型
     * @return true=支持，false=不支持
     */
    public boolean isSupported(String fileType) {
        return strategies.stream()
                .anyMatch(strategy -> strategy.support(fileType));
    }
}
