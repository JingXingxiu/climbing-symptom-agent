# Agent 行为观测与阅读顺序

这部分用于看清一次分析经历了哪些操作、哪里失败、时间和 Token 花在哪里。它复用 Spring Boot Actuator、Micrometer Observation、OpenTelemetry 和 Jaeger，不建设另一套监控平台。

## 先理解三种记录

| 记录 | 回答的问题 | 保存位置 |
| --- | --- | --- |
| Session、Step、Checkpoint | 任务到哪一步、当前分支是什么、退出后从哪里继续 | AI PostgreSQL；属于业务状态 |
| ModelCall | 哪一代执行发起了调用、是否采纳、平台、Token、错误和 Trace ID | AI PostgreSQL；属于适度的调用元数据 |
| Trace / Span、指标 | 一次执行内部各阶段的先后与耗时；一段时间内的成功、失败和调用分布 | 应用内指标与可选 Jaeger；用于观测 |

Checkpoint 不记录每个函数调用。Span 也不能替代事务、幂等键、执行代次或 Checkpoint：即使没有监控服务，业务状态仍由原来的数据库逻辑决定。

目前观测范围是 AI 服务的 HTTP 请求和内部 Agent 执行。其他服务、Gateway 与供应商的服务端链路不会因为启用 Jaeger 自动变成完整跨服务追踪；传入标准 W3C `traceparent` 时由框架承接。`X-Trace-Id` 是返回给调用方的关联标识，不信任客户端传入的同名头。

## 默认运行

AI 服务默认保留本地指标和 Trace 关联，**不向外部 Collector 导出**。管理端口默认只监听 `127.0.0.1:18085`，不通过前端 Gateway 转发。

AI 服务正常启动后，在本机访问：

```powershell
Invoke-RestMethod http://127.0.0.1:18085/actuator/health
Invoke-RestMethod http://127.0.0.1:18085/actuator/metrics
Invoke-WebRequest http://127.0.0.1:18085/actuator/prometheus -UseBasicParsing
```

`metrics` 返回已注册的指标名；按返回名称继续请求 `/actuator/metrics/{name}` 即可查看计数或计时统计。Prometheus 文本端点可供后续采集，本轮不额外部署 Prometheus 或 Grafana。

主要业务指标为 `agent.model.calls`（成功、失败、旧结果丢弃）、`agent.decisions`（缩小范围、澄清矛盾、输出结果）和 `agent.tokens`（平台、模型、输入/输出方向）。`agent.execution`、`agent.web_search` 等 Observation 同时提供阶段计时。计数和计时只在实际执行后出现，供应商未返回的 Token 不补成精确用量。

## 打开现成的 Trace 时间线

在 `infra/` 执行：

```powershell
docker compose --profile observability up -d jaeger
docker compose --profile observability logs --tail=100 jaeger
```

然后在**启动 AI Java 进程的终端**设置：

```powershell
$env:AI_OTLP_ENABLED = 'true'
$env:AI_OTLP_TRACES_ENDPOINT = 'http://127.0.0.1:4318/v1/traces'
$env:AI_TRACE_SAMPLING_PROBABILITY = '1.0'
# 在这个终端按原来的方式启动 ai-service；IDE 启动需在 Run Configuration 填同名变量。
```

`infra/.env` 只用于 Compose 变量替换，不会自动传给宿主机 Java 应用。Jaeger 使用固定的 v2.22.0 单镜像，默认将 Collector 与 Query 合并运行，使用内存保存 Trace；本机页面为 <http://127.0.0.1:16686>。实现依据见 [Jaeger 官方入门说明](https://www.jaegertracing.io/docs/2.22/getting-started/)。

发起一次已有的 Agent `advance` 请求后，读取响应 `X-Trace-Id`；也可以在所属任务的只读观测接口找到持久化 ModelCall 的 Trace ID：

```text
GET /api/ai/v1/agent-sessions/{sessionId}/observations
```

这个接口沿用当前用户身份与 Session 归属校验。它返回业务步骤与脱敏调用元数据，用于关联任务及 Jaeger 查询，不返回完整 Prompt、答案原文、图片签名 URL 或密钥。

Jaeger 中选择 `ai-service`，查找刚刚的请求或 Trace ID。一次执行会拆出 `agent.*` 阶段，例如联网检索、图片读取、模型请求和输出校验。工具未启用、工具不可用、模型请求失败、结构化结果不合法、旧响应未被采纳，需要结合各阶段状态和持久化记录区分。失败可以结束一个 Span；工具返回“不可用”也应有明确结果标记。

原 ModelCall 的执行耗时覆盖外部处理阶段，不能当作供应商纯推理耗时。需要比较搜索、图片读取与模型 HTTP 调用时，查看对应阶段的 Span / Timer。供应商不提供 Token 时保持未知，不能把缺失值当成精确的零消耗。

停止可选 UI：

```powershell
docker compose --profile observability stop jaeger
```

Jaeger 重启后内存 Trace 会丢失；数据库中的 Session、Step、Checkpoint、ModelCall 不受影响。数据库中的旧 Trace ID 可能因此查不到可视化明细。应用的业务执行不依赖 Collector 可用性。

## 字段与隐私边界

模型平台、配置内的模型名、阶段和有限状态适合用于指标标签。Session ID 可作为 Trace 的高基数字段，便于查某次任务，但不能进入指标标签，避免为每个用户和任务不断创建新的时间序列。

不把用户症状原文、完整 Prompt / Response、照片、签名 URL、授权头或密钥写入观测标签。不把异常的原始消息直接当作标签。记录结构化决策类型、错误类别和必要的数量即可；不要求模型暴露完整思维链。

原生 Spring AI Span 的错误事件也需要脱敏：关闭 Prompt 日志并不能阻止 SDK 异常原文进入 Trace。`SafeModelErrorObservationHandler` 仅替代默认 fallback Tracing handler，在原生 `gen_ai.client.operation` 记录错误前替换为固定消息；不改变业务异常，也不覆盖 W3C receiver/sender 的优先级。真实 Boot / OTel 内存 Exporter 测试检查了最终导出的事件。

## 按这个顺序读代码

1. **`AgentTelemetry`**：先看 `observe` 如何开始/结束一次操作并记录异常，再看 `inSession` 如何关联任务，最后看决策、结果和 Token 计数。理解 Observation、Trace 与指标分别起什么作用。
2. **HTTP Trace 关联过滤器与 `ApiProblemAdvice`**：响应头、正常执行和 Problem 的 Trace ID 为什么能对应；任意客户端头为什么不能直接成为可信关联 ID。
3. **`AgentServiceImpl.advanceSession`**：观察预占、事务外处理与提交结果如何被包在同一次执行内。原来的事务与行锁边界保持不变。
4. **`SpringAICompatibleModel.analyze`**：查看搜索、图片读取、模型 HTTP 请求、结构化输出校验的阶段划分，以及手工创建的 ChatModel 如何使用 ObservationRegistry。
5. **任务观测查询**：看归属校验、Step/ModelCall 查询、脱敏 DTO 组装；解释它与原有任务状态接口的用途区别。

看完应能解释：一轮 Agent 很慢时如何定位阶段；工具“不可用”和模型“失败”如何区分；失败或迟到结果为什么仍可能消耗 Token；采样关闭后为何还能恢复任务；Session ID 为什么不能随便作为指标标签。

## 验证边界

Compose 配置校验不代表已经启动 Jaeger。离线测试使用 HTTP stub 或测试模型验证 Span、指标和 Trace 关联，不需要请求真实付费模型。

本轮不自动启动 Docker、执行 AI SQL 或写入现有数据库。真实供应商、微信真机、完整跨服务链路和实际 Collector 接收情况需要在本机运行时分别验收，不能用单元测试通过代替这些结果。
