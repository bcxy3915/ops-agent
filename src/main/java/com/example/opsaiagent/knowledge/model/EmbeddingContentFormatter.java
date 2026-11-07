package com.example.opsaiagent.knowledge.model;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 保留存量向量的默认文本格式；不支持框架自定义模板。
 * 兼容规则及 Apache-2.0 来源见 docs/licenses/spring-ai-compatibility.md。
 */
public final class EmbeddingContentFormatter {
    /**
     * 禁止实例化工具类。
     * @return 无返回值
     */
    private EmbeddingContentFormatter() { }

    /**
     * 按迁移前的默认模板格式化正文和元数据。
     * @param document 项目文档
     * @param mode 元数据模式，默认模板未配置排除字段
     * @return 与旧默认模板一致的模型输入
     */
    public static String format(Document document, EmbeddingMetadataMode mode) {
        Objects.requireNonNull(mode, "元数据模式不能为空");
        Map<String, Object> metadata = switch (mode) {
            case NONE -> Map.of();
            case ALL -> document.getMetadata();
            // 保持旧默认过滤器的 HashMap 收集顺序，不能排序或直接改用原始 Map。
            case EMBED, INFERENCE -> document.getMetadata().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        };
        String header = metadata.entrySet().stream()
                .map(entry -> "{key}: {value}".replace("{key}", entry.getKey())
                        .replace("{value}", entry.getValue().toString()))
                .collect(Collectors.joining(System.lineSeparator()));
        return "{metadata_string}\n\n{content}".replace("{metadata_string}", header)
                .replace("{content}", document.getText() == null ? "" : document.getText());
    }
}
