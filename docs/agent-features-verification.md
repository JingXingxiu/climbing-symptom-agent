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

## 最终结果评审追加验证

- 全量 `mvn -o package`：433 项测试，431 项通过、2 项跳过、0 失败、0 错误，所有后端 JAR 打包通过。跳过项为既有按需性能基准和明确 opt-in 的真实模型评测。
- 状态机新增场景验证 A+B 通过、一次纠正与复审、弃权、复审仍不通过、供应商失败、追加调用额度不足，以及 B/A' 期间暂停后的迟到响应拒绝。每次实际生成/评审请求独立占用 Session 和日额度，同一代次最多一条 STARTED。
- 真实 SDK 仅连接本机假供应商，验证 B 接收同一批图片字节、严格 JSON 评审输出、Token 统计、A 沿用原资料纠正、无隐藏重试。没有发起真实供应商评审。
- 十个合成固定案例及手动评测脚本已提供，报告逻辑有离线测试；没有实际评审质量或准确率数据。
- 前端类型检查、微信小程序构建及既有支付回归检查通过；AI 同步响应窗口已与最多四次生成/评审请求配套调整。
- 评审默认关闭；启用需配置与 A 不同的服务端白名单模型。无需新增 SQL 迁移，继续使用现有 purpose、response_metadata 和 Checkpoint State。

## 验证边界

本轮没有启动 Docker、调用真实供应商、导入真实语料或运行真实 PostgreSQL/pgvector 查询。上述测试不能作为实际部署、检索质量、医疗建议有效性或吞吐能力的证明。Jaeger 使用内存保存 Trace；业务恢复仍依赖原有 PostgreSQL Checkpoint。
