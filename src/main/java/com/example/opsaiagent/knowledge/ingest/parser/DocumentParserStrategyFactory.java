package com.example.opsaiagent.knowledge.ingest.parser;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档解析策略工厂类
 */
@Component
@RequiredArgsConstructor
public class DocumentParserStrategyFactory {

    private final List<DocumentParserStrategy> strategies;

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
