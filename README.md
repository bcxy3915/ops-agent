# Ops Agent

通用运维智能体 —— 用自然语言诊断服务问题的 AI 应用

## 简介

基于 Spring AI + RAG + Function Calling，实现服务无关的运维诊断平台。
任何暴露 Spring Boot Actuator 端点的服务，都能被自动纳管。

## 技术栈

- Java 21 / Spring Boot 3.5 / Spring AI 1.1
- Pgvector / PostgreSQL 16 / MyBatis-Plus
- DeepSeek（Chat）+ 阿里云百炼（Embedding）

## 核心能力

- RAG 知识库问答
- Function Calling 工具调用（健康检查/指标查询/指标发现）
- 多轮对话记忆
- 数据库驱动的服务注册中心

## 快速开始

1. 启动 Pgvector 容器
2. 配置环境变量
3. 启动应用

## 项目阶段

- P1：RAG + Tool Calling ✅
- P2：多轮对话 + 注册中心升级（进行中）
- P3：待规划
