# Agent 观测与空库 RAG 验证记录

日期：2026-11-03。

本次提供 Agent 阶段观测、W3C Trace 关联、可选 Jaeger、脱敏任务执行记录、只读 RAG 检索，以及检索资料从 AI 到 History 历史结果和前端的传递。没有导入真实知识资料。

## 已执行

- 后端全量 `mvn -o test`：409 项，408 项执行通过，1 项既有按需性能基准跳过；失败和错误均为 0。
- 最终增加 2 项观测隐私/无 Tracer 测试，并复跑 AI 模块全部 111 项后打包通过。当前所有模块报告合计 411 项：410 项通过、1 项跳过、0 失败、0 错误。
- 本机 HTTP 测试验证 W3C `traceparent`、响应 `X-Trace-Id`、执行 Trace ID 和 Problem 的关联。
- 真实 Boot 自动配置和 OTel 内存 Exporter 验证：SDK 错误回显不会进入最终 Span 事件，业务异常控制流保持不变。普通高优先级 Handler 无法先于 Boot Tracing 分组，最终实现替代默认 fallback Handler。
- 空库零 Embedding 调用、关闭/故障/未命中状态、向量维度及数值校验、物化 CTE 与绑定参数测试。
- 真实 Spring AI SDK 连接本机 HTTP 假供应商，验证多模态请求、检索来源与版本、拒绝模型伪造来源、内部地址隐藏和阶段观测。
- History 来源快照保存、重复提交回放、同一任务不同来源冲突，以及不合法来源校验。
- 前端 TypeScript 检查、微信小程序构建、现有模拟支付回归检查。
- 全部后端服务可启动 JAR 打包通过；最终 AI 隐私修复后单独重新测试并打包 AI 服务。
- OpenAPI YAML 解析与内部引用检查；Compose 默认环境及 `observability` profile 配置解析。

## 运行前需要处理

原有 AI V001/V002、History V003 仍由开发者审查并手动应用。新 History V004 增加两张结果表的 `source_references` 列，需与更新后的 History 代码配套部署；本轮没有执行任何 migration。

Jaeger 是同一 Compose 组中的可选容器，按 [观测指南](agent-observability.md) 启用。OTLP 导出默认关闭。资料库为空时可按 [RAG 指南](rag.md) 显式打开检索，观察 EMPTY；数据库表未建立则是 UNAVAILABLE。

## 验证边界

本轮没有启动 Docker、调用真实供应商、导入真实语料或运行真实 PostgreSQL/pgvector 查询。上述测试不能作为实际部署、检索质量、医疗建议有效性或吞吐能力的证明。Jaeger 使用内存保存 Trace；业务恢复仍依赖原有 PostgreSQL Checkpoint。
