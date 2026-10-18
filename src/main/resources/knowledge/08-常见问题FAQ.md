# 常见问题 FAQ

> 文档元数据
> - 版本：v1.0
> - 目标：覆盖日常使用高频问题，可直接供对话问答检索（RAG 命中）
> - 更新：随使用反馈持续补充

## 1. 使用类问题

### Q1. 如何注册一个服务到运维智能体？
**A**：通过前端「服务管理」页或调用 `POST /api/services` 注册。必填：name、baseUrl；建议填写 healthPath（默认 `/actuator/health`）、metricsPath（默认 `/actuator/metrics`）、owner（负责人）。注册后系统会进行健康检查并采集指标，状态变为 UP 即成功。

### Q2. 如何查看服务的健康状态？
**A**：前端「服务管理」页可查看所有服务状态（UNKNOWN/UP/DOWN/DEGRADED）。也可以直接问对话：「查看 todo-service 的状态」。原始端点：`GET http://localhost:8081/actuator/health`。

### Q3. 如何查看监控指标？
**A**：前端「指标监控」页选择服务和指标，查看历史曲线。也可以问对话：「查看 todo-service 的 CPU 使用率」。系统支持 jvm.*、system.*、http.server.requests、hikaricp.* 等指标，详见《03-指标字典.md》。

### Q4. 如何配置告警规则？
**A**：前端告警配置页创建规则：选择服务、指标、比较符（>/</>=/<=）、阈值、持续时长（duration_seconds）、静默期（silence_seconds），启用后生效。默认阈值参考《03-指标字典.md》。

### Q5. 运维智能体能回答哪些问题？
**A**：支持三类：
1. 知识库问答（部署、故障、应急预案、指标字典等官方手册内容）
2. 实时运维查询（服务状态、指标、健康）——会调用工具获取真实数据
3. 故障诊断（针对告警快照生成根因分析与处置建议）

## 2. 故障与告警类问题

### Q6. 收到告警通知后第一步做什么？
**A**：① 登录系统查看告警事件与服务状态；② 判断事件级别（见《05-应急预案.md》）；③ 按《02-故障处理手册.md》对应场景处置；④ 超时限未解决按升级路径上报。

### Q7. 告警经常误报/风暴怎么办？
**A**：① 检查阈值是否过敏感，参考《03-指标字典.md》调整；② 增加 duration_seconds（持续时长）；③ 设置 silence_seconds（静默期）。详见《02-故障处理手册.md》第 6 节。

### Q8. 服务显示 DOWN 但实际能用，为什么？
**A**：可能原因：健康检查路径配置错误、网络不可达（防火墙/容器网络）、Actuator 未暴露 health 端点。检查 `ops_service` 中该服务的 health_path 与 base_url，并确认端点真实返回 `{"status":"UP"}`。

### Q9. 故障诊断结果（root cause）是怎么来的？
**A**：告警触发后系统自动抓取故障快照（failure_snapshot），调用 LLM 分析快照与历史指标，生成根因（root_cause）、证据（evidence）与处置建议（suggestions），存入 diagnosis_record 表。诊断结果仅供决策参考，处置前需人工确认。

### Q10. 如何触发一次故障诊断？
**A**：告警自动触发诊断；也可对已有快照手动触发诊断（`POST /api/diagnoses/snapshot/{id}`）。诊断记录可在前端查看历史。

## 3. 部署与配置类问题

### Q11. 如何启动整个系统？
**A**：① `docker compose up -d` 启动数据库；② 启动 ops-agent 后端（`mvnw spring-boot:run -Dspring-boot.run.profiles=local`）；③ 启动 todo-service；④ 前端 `npm run dev`。详见《04-部署手册.md》。

### Q12. 数据库端口为什么是 5433？
**A**：项目 docker-compose 将容器内 5432 映射到宿主机 5433（`${DB_PORT:-5433}:5432`），避免与本地已安装 PostgreSQL（5432）冲突。

### Q13. 修改知识库文档后如何生效？
**A**：将 md/pdf 放入 `src/main/resources/knowledge/` 后重启应用。DocumentLoader 会按 source 去重增量加载：文档缺失则删除旧向量并重建，已存在则跳过。

### Q14. 如何查看系统有哪些数据表？
**A**：共 13 张表：alert_event、alert_rule、metric_sample、ops_audit_log、ops_conversation、ops_message、ops_service、ops_user、vector_store、notification_channel、notification_record、failure_snapshot、diagnosis_record。可用 psql 查看：`docker exec ops-postgres psql -U postgres -d opsdb -c "\dt"`。

### Q15. LLM 诊断或问答报错怎么处理？
**A**：① 检查 LLM 配置（API Key、模型名）是否正确；② 查看 diagnosis_record 的 error_message；③ 确认网络可访问模型服务；④ 重启后重试。详见《02-故障处理手册.md》第 7 节。

## 4. 安全与权限类问题

### Q16. 谁能访问管理功能？
**A**：系统采用 JWT 认证 + 角色权限。审计日志（audit）与用户管理（users）仅管理员（ADMIN）可见；对话、服务、指标对登录用户开放。未登录用户仅可访问对话首页。

### Q17. 告警通知渠道如何配置？
**A**：在 `notification_channel` 表中配置渠道（类型、目标地址、启用状态）。通知失败可在 `notification_record` 中查看状态与错误信息。

## 5. 产品功能类问题

### Q18. 前端是管理端还是对话端？
**A**：是一体化工作台：对话为默认首页（`/` 重定向 `/chat`），侧栏集成服务管理、指标监控、审计日志、用户管理。日常使用入口是对话，管理配置在侧栏。

### Q19. 是否支持用户上传自己的运维文档？
**A**：当前版本知识库为内置官方手册（本目录 7 类文档）。用户上传文档能力规划在 V2 阶段（增量知识库），当前可先把需要的内容按官方手册格式补充到 knowledge 目录。

### Q20. 如何查看故障案例/复盘记录？
**A**：前端可查看诊断记录与故障历史；正式复盘按《09-故障案例模板.md》沉淀，与 failure_snapshot / diagnosis_record 数据联动。
