package com.example.opsaiagent.knowledge.ingest.parser;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

public interface DocumentParserStrategy {

    /**
     * 是否支持该文件类型
     * @param fileType 文件类型
     * @return true 表示支持，false 表示不支持
     */
    boolean support(String fileType);

    /**
     * 解析文件
      * @param resource 文件资源
     * @return 文件内容列表
     */
    List<Document> parse(Resource resource);
}
