# ai-service

有限步骤症状分析，采用 MVC + MyBatis-Plus。业务入口是 `AgentServiceImpl`，共十三个公开方法；学习时先看每个方法主流程，再看它实际调用的辅助方法。

- [逐方法阅读顺序](../../../docs/agent-service-reading.md)
- [数据库与 Entity 说明](../../../docs/agent-database-learning.md)
- [HTTP 契约](../../../docs/api/openapi.yaml)
- 配置样例：`infra/nacos-config/ai-service-dev.yaml`

## 已实现

创建/读取、保存回答、单步执行、暂停/恢复/超时接管、取消、历史改答分叉、模型切换、步骤历史、结果读取与 History 定稿。模型网络请求在本地事务之外，回复只有在状态、执行代次、起点 Checkpoint 都匹配时才提交。

输入经 Feign 从 History/Image 获取，标注和视角冻结在快照中；图片读取 URL 在执行前刷新。History 在自己的事务中追加结果版本、更新当前结果和 Assessment。跨服务失败保留候选，可按 Session ID 重试定稿；没有分布式事务。

## 用户每日 AI 额度

公开接口 `GET /api/ai/v1/quota` 使用 `X-User-Id`，返回 `userId`、`usageDate`（`YYYY-MM-DD`）、`zoneId`（`Asia/Shanghai`）、`planCode`、`usedModelCalls`、`dailyModelCallLimit`、`remainingModelCalls` 和可空的 `entitlementValidUntil`。查询只读，不创建或重置用量。

每次 advance 先在事务外调用 payment-service 的 `GET /internal/payment/entitlement` 获取当前权益。免费方案每日 10 次、PLUS 每日 50 次及 30 天有效期由 payment-service 管理；AI 不缓存或自行推断套餐。权益查询失败、用户不匹配或响应无效统一返回 `503 PAYMENT_ENTITLEMENT_UNAVAILABLE`，不会降级为无限免费额度。续购或套餐变化在下一次查询/执行生效，同日已用次数保留；额度下降后剩余次数最小为零。

AI 按用户和上海业务日期在 PostgreSQL 中共享用量，跨 Session、跨实例共同计数。advance 的现有短事务在写入 Session/ModelCall 前使用条件 upsert 原子预占一次；耗尽返回 `429 AI_DAILY_QUOTA_EXCEEDED`，不会读取图片或调用模型。事务回滚不扣次数，已提交的图片/模型失败、暂停后丢弃的旧响应仍计为一次执行尝试。创建、查询、回答、恢复和套餐查询不扣次数；Session 本身的默认 20 次模型调用、40 步限制仍保留。

新日期写入新行，无需定时重置。新表脚本为 `database/future/ai_db/V002__FUTURE_NOT_APPLIED_user_daily_usage.sql`，仍是 FUTURE / NOT APPLIED，必须显式审查后手工应用，没有自动迁移；本次实现未执行该 SQL 或既有 AI schema。

## Payment 权益查询熔断

`AgentQuotaService.getEntitlement` 通过 `PaymentEntitlementGuard` 调用现有 Feign Client，`getQuota` 和 `advance` 共享这一边界。Sentinel 的出站资源名为 `ai:payment:entitlement`，仅传输故障（Feign 无 HTTP 状态或 `IOException`）和 HTTP 5xx 计入熔断异常；HTTP 4xx、无效权益响应和本地运行时错误不会误记为下游故障。所有查询失败和熔断拒绝仍返回 `503 PAYMENT_ENTITLEMENT_UNAVAILABLE`；熔断时 detail 包含 `temporarily blocked`，不会访问 Payment、预占每日额度、读取图片或调用模型，也不会生成 FREE 或无限额度兜底。

默认配置如下，可通过 Nacos 样例或环境属性覆盖，启动时校验并注册规则；修改配置后需重启实例。无需 Dashboard 即可生效。`enabled=false` 只关闭熔断检查，远程查询失败仍返回 503。

```yaml
ai:
  payment-entitlement-breaker:
    enabled: true
    exception-ratio: 0.5
    minimum-request-amount: 5
    stat-interval-ms: 10000
    open-seconds: 10
```

达到最少 5 个已完成请求后，10 秒统计窗口内的异常比例**大于** 0.5 才打开熔断器；等于阈值时继续放行。打开后 10 秒停止请求 Payment，到期允许一个半开探测，成功恢复，传输/5xx 失败则重新熔断。比例取值范围为 `[0, 1]`，配置为 1 时不会超出阈值；其余三个数值必须为正整数，`open-seconds` 最大为 2147483，防止 Sentinel 内部秒转毫秒溢出。

熔断器状态和请求统计属于**单个 AI 实例**，各实例独立恢复，不提供跨实例全局状态。数据库每日额度仍按用户共享。Feign 远程调用保持在本地事务外，`NEVER`/`MANDATORY` 事务约束和 Session 预算均保留；没有增加自动重试或模型切换。

依赖仅使用 Alibaba BOM 管理的 `sentinel-core` 1.8.9，不启用 Feign fallback DTO。规则使用官方 `DegradeRule` 异常比例策略，出站 `SphU.entry` 与显式 `Tracer.traceEntry`；参考 [Sentinel 熔断文档](https://sentinelguard.io/zh-cn/docs/circuit-breaking.html) 和 [基础 API](https://sentinelguard.io/zh-cn/docs/basic-api-resource-rule.html)。离线测试使用真实 Sentinel 引擎、Mockito Payment 与可控时间验证打开、拒绝、半开成功/失败、禁用和 4xx，不调用外部服务或真实模型。

## 模型和搜索

业务提示词放在 `src/main/resources/prompts/symptom-agent.md`，不使用 v1 等编号后缀。Java 在启动时加载资源，每次调用追加输出 Schema；当前不配置提示词热更新或过滤服务。不同供应商的协议参数仍由模型适配处理。

默认使用智谱 `zhipu/glm-4.6v-flash`，这是支持图片输入的免费视觉模型，仍受平台账户和并发限制。Qwen、智谱、OpenAI 通过服务器配置白名单选择，前端不能提供任意地址或密钥；真实模型缺配置返回明确错误。

`FakeAgentModel` 仅位于 `src/test/java`，用于离线测试，不会打入正式 JAR，不是熔断或模型失败时的兜底。

使用 Spring AI 2.0.2 的多模态模型适配。每次执行至多一次模型请求，无自动重试；搜索由 Java 显式预检索一次，再把资料与图片交给模型，不是模型无限自主循环。`SearchTool` 对接独立智谱搜索 API，默认关闭，失败会明确表示没有联网资料。没有配置即无联网能力，也没有“模型免费等于搜索免费”的假设。

本机 MinIO 图片由 AI 服务限量下载并转换为多模态输入，云模型不直接访问 localhost URL。允许的图片主机、图片大小、网络超时均可配置。搜索只发送结构化症状/部位构造的公开检索词，默认不保存原始 Prompt/Response。

智谱视觉模型通过提示词约束 JSON，并在 Java 中解析和校验，不发送仅文本模型支持的 `response_format`。适配器发送 `thinking: {type: disabled}` 关闭思考模式，避免推理内容占用当前有限的输出预算。

本机密钥放在模块目录的 `ai-service.local.properties`，已加入 Git 忽略规则，不放进 `src/main/resources`，因此不会打入 JAR。启动配置依次尝试相对当前工作目录的 `./ai-service.local.properties`、`./service/ai/ai-service.local.properties`、`./backend/service/ai/ai-service.local.properties`，兼容从模块、backend 或项目根目录启动。

本机文件需要以下属性；密钥自行填写，其他值均可公开：

```properties
ai.workflow.default-provider=zhipu
ai.workflow.default-model=glm-4.6v-flash
ai.models.profiles.zhipu.provider=zhipu
ai.models.profiles.zhipu.model=glm-4.6v-flash
ai.models.profiles.zhipu.base-url=https://open.bigmodel.cn/api/paas/v4
ai.models.profiles.zhipu.api-key=YOUR_LOCAL_KEY
ai.search.enabled=false
```

也可以不使用本机文件，改为通过服务环境变量提供 `ZHIPU_API_KEY`。联网搜索独立配置，免费模型不代表搜索也免费。

官方资料：[GLM-4.6V-Flash](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash)、[价格](https://docs.bigmodel.cn/cn/guide/start/pricing)、[OpenAI 兼容协议](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction)。

## 启动前

1. 手工审查并执行 AI SQL 和 History V003，均尚未应用，未配置自动迁移。
2. 将 ai-service 与 Gateway 的 Nacos YAML 样例发布到当前环境。修改样例文件不会自动更新 Nacos。
3. 配置 `AI_POSTGRES_*`，并使用上面的本机文件或环境变量配置智谱密钥；密钥不写进公共 YAML 或前端。本机文件会覆盖 Nacos 中同名的模型设置。
4. 如需切换其他平台，先更新本机文件中的默认模型和对应 profile；只用 Nacos/环境变量时，可设置 `AI_MODEL_PROVIDER`、`AI_MODEL_NAME` 和对应密钥。联网另设 `AI_SEARCH_ENABLED=true`、`ZHIPU_SEARCH_API_KEY`；这里的搜索服务与模型平台独立。
5. Gateway 的 AI 路由与前端 advance 超时为 150 秒；执行期限默认 120 秒。旧执行超过期限后可由显式恢复接管。

离线测试无需数据库、Nacos、MinIO、模型或搜索密钥。数据库真锁/跨实例竞争、真实供应商与微信设备完整联调尚未执行。RAG/MCP/Skills、定时后台任务和流式响应不在当前实现中。
