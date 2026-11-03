# AI 手部症状与不适辅助分析（攀岩 MVP）

这是一个用于学习 Spring Cloud 和 AI Agent 的 Java 后端实习作品。前端帮助用户结构化表达手部不适的位置、表现和触发情况；疼痛只是可选症状之一，系统不提供疾病诊断。仓库包含前端 Mock 演示、后端参考实现、数据库脚本和本地基础设施，完整真实环境联调仍需按下方边界逐项验证。

## 已交付

- Vue 3 + uni-app + TypeScript 微信小程序；
- 痛、酸、胀、麻、肿、无力、僵硬、其他/不确定八类症状多选；
- 真正的交互式 3D 手部：旋转、缩放、稳定粗区域 ID、点击高亮、左右手镜像和视角截图；
- 3D 截图、标准掌/背/侧图、用户多图三种统一视觉来源；
- 点选、画圈、自由绘制与归一化 Annotation；
- 有限步骤 Mock Agent，包含正常追问和矛盾确认；
- Agent 后端参考实现：暂停恢复、历史改答分叉、可替换模型、识图与可选联网检索、History 结果定稿；
- 可配置最终结果评审：B 检查 A 的候选，最多一次纠正与复审，复用调用预算和观测；附默认不调用外部 API 的合成案例评测入口；
- 本地历史、草稿及步骤恢复；
- 独立 `payment-service`：模拟套餐购买、幂等支付确认、权益续期和 AI 每日额度；
- `base_db`、`image_db`、`history_db`、`payment_db` 当前 SQL；
- 明确标记 **FUTURE / NOT APPLIED** 的 `ai_db` + pgvector SQL；
- 五服务 OpenAPI 3.1、架构/开发/License 文档和 Terra 后端模板 Prompt；
- 两个 PostgreSQL、MinIO、RabbitMQ、Nacos、Sentinel Dashboard、Swagger UI 组成的单一 Compose 环境。

## 快速验证

```powershell
cd frontend
pnpm install
pnpm run type-check
pnpm run build:mp-weixin
```

将 `frontend/dist/build/mp-weixin` 导入微信开发者工具。默认使用本地 Mock，无需 Java 后端。真实设备还应按 [前端视觉验收清单](docs/history/2026-09-29-pre-development/development-guide.md) 验证 WebGL、触摸、截图和内存释放；该归档文档中的早期后端方案不代表当前实现。

本地基础设施：

```powershell
cd infra
Copy-Item .env.example .env
docker compose up -d --build --wait
```

这一条 Compose 命令会启动整组 7 个基础设施容器。它只创建运行环境和四个业务逻辑数据库，并在独立 AI PostgreSQL 中启用 pgvector；不会自动执行任何业务 migration，也不会执行未来 AI Schema。已有卷需手动创建新增的 payment_db。端口、初次登录、日志、停止和清理命令统一见 [基础设施说明](infra/README.md)。

启动后在 `http://localhost:18081` 查看可交互 API 文档；它直接读取 [OpenAPI Contract](docs/api/openapi.yaml)，Gateway 运行后可使用 `Try it out` 发起请求。

## 关键入口

- [开发指南](docs/development-guide.md)
- [早期交付验证记录](docs/history/2026-09-29-pre-development/verification-report.md)
- [早期架构设计记录](docs/history/2026-09-29-pre-development/architecture.md)
- [OpenAPI Contract](docs/api/openapi.yaml)
- [数据库说明](database/README.md)
- [Agent Service 阅读顺序](docs/agent-service-reading.md)
- [模拟支付与 AI 额度](docs/payment-service.md)
- [Sentinel 限流与熔断](docs/sentinel.md)
- [Agent 行为观测与 Jaeger](docs/agent-observability.md)
- [最终结果模型评审与固定案例](docs/agent-review.md)
- [RAG 检索与空库行为](docs/rag.md)
- [开源资源与 License](docs/open-source-and-licenses.md)
- [早期 Terra Java 后端模板 Prompt](docs/history/2026-09-29-pre-development/terra-backend-template-prompt.md)
- [手部资产复现说明](tools/README.md)

## 验证范围与尚未完成的部分

当前已实现 Base/Image/History 业务、独立 Payment 模拟购买与权益、AI 每日额度、Gateway 路由与 JWT 过滤、Sentinel 入站限流及权益查询熔断、Agent 编排与国产/OpenAI 兼容模型和搜索适配。

- Agent 已接入阶段观测、脱敏执行记录、Actuator 指标和可选 Jaeger；RAG 已实现只读检索与空库分支，尚未导入真实资料。
- 真实 Nacos 部署联调、供应商 API、PostgreSQL/pgvector 检索和图片后台预处理尚未完成实际环境验证。
- Sentinel 已通过真实引擎和本机 HTTP 测试，演示阈值未做容量压测；本机 HTTP stub 不代表完整部署链路已验证。
- 图片后台处理的设计为数据库状态与定时任务；应用当前未接 RabbitMQ，不引入 Outbox/Inbox。Compose 中保留 RabbitMQ 容器不代表应用已经使用 MQ。
- AI SQL（含每日额度 V002）与 History V003/V004 标记为 **NOT APPLIED**，Payment SQL 也需显式执行。Compose 不会自动执行这些迁移，请先阅读数据库说明并按需应用。

本地密钥、环境变量文件、用户图片、数据库卷、构建产物和个人简历不随源码发布。示例中的默认账号密码仅用于本机学习，公开部署前必须替换。
