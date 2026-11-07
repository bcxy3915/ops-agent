# Spring AI 兼容规则来源

本次迁移参考 Spring AI 2.0.2 的 `TokenTextSplitter`、`TextSplitter`、`MarkdownDocumentReader` 与 `DefaultContentFormatter`，将本项目使用的固定规则调整为直接使用 JTokkit、CommonMark 和项目文档类型的实现。原组件的配置构建器、自定义模板、媒体文档等功能不在本项目兼容范围内。

原项目：https://github.com/spring-projects/spring-ai

原版权声明：Copyright 2023-present the original author or authors.

原许可：Apache License, Version 2.0，全文见 [Apache-2.0.txt](Apache-2.0.txt)。本说明仅针对上述兼容实现，不改变其他项目文件的许可。

修改说明：移除 Spring AI 运行时类型，使用项目文档保存评分与分块元数据；Markdown 明确按 UTF-8 解码；token 块数达到上限时显式失败，避免把剩余内容追加为超大块。默认格式化保留原元数据收集顺序与模板替换顺序。
