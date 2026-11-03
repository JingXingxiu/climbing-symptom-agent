# Sentinel 接入与验证

本轮实现两处实际保护：Gateway 的分析执行、图片上传分组限流，以及 AI 到 Payment 权益读取的异常比例熔断。没有新增服务或数据库，不使用 Redis，也不把熔断兜底成免费权益或成功结果。

## Gateway 入站限流

使用 Alibaba BOM 管理的官方 `sentinel-spring-cloud-gateway-v6x-adapter` 与 `sentinel-transport-simple-http` 1.8.9。限流算法和统计窗口由 Sentinel 实现；只增加一个 POST 方法筛选层。

| 资源 | 路径 | 开发示例阈值 |
| --- | --- | --- |
| `gateway-agent-advance` | POST `/api/ai/v1/agent-sessions/{sessionId}/advance` | 2 QPS |
| `gateway-image-upload` | POST `/api/image/v1/pictures` | 5 QPS |

这是每个 Gateway 实例上的 API 分组合计，不是每用户额度，不是集群全局限流，也不是经过容量压测的推荐阈值。不同 Session 共享分析分组；上传分组独立计数。其他方法和路径不受这两条规则限制。

JWT 过滤器顺序 -100，Sentinel 过滤器 -90。未鉴权请求保留原响应，不通过伪造身份头绕过鉴权。超限请求不进入下游，返回 `429 application/problem+json`，含 `code=GATEWAY_RATE_LIMITED`、`traceId` 和 `Retry-After: 1`。它与 `AI_DAILY_QUOTA_EXCEEDED` 区分，前端不会把普通限流当成需要购买套餐。

规则入口是 `backend/gateway/src/main/java/org/javaup/gateway/config/GatewaySentinelConfiguration.java`。Java/本地默认禁用；开发 Nacos 样例显式启用。配置键见 Gateway README，支持开关、两个 QPS、Dashboard、transport 端口和回连地址。

## AI 出站熔断

`PaymentEntitlementGuard` 在原 Feign 客户端外用官方 `SphU.entry` 建立 OUT 资源 `ai:payment:entitlement`，由额度查询和分析执行共同复用。仅将传输故障、HTTP 5xx 纳入异常统计，4xx 不触发熔断。

默认规则：统计窗口 10 秒、最少 5 次请求，异常比例**严格大于** 0.5 时打开，10 秒后进入半开探测；探测成功恢复，失败重新打开。状态属于单个 AI 实例，规则使用 `ai.payment-entitlement-breaker.*` 配置。

熔断或调用失败返回 `503 PAYMENT_ENTITLEMENT_UNAVAILABLE`。保护发生在 AI 执行预占事务之前，因此被拒绝时不会扣日额度、创建 ModelCall 或调用模型；保持现有 NEVER/MANDATORY 事务边界，不增加自动重试。选择只读权益查询作为保护边界，避免对模拟支付确认之类写操作进行隐式重试。

AI 只依赖 `sentinel-core`，不自动给所有 Feign 客户端或所有 HTTP 接口安装 Sentinel，不接控制台 transport。AI 熔断规则可脱离 Dashboard 工作。

## 配置与运行

更新的配置样例：

- `infra/nacos-config/gateway-dev.yaml`
- `infra/nacos-config/ai-service-dev.yaml`

发布到 Nacos 后重启相应 Java 服务。当前配置不做热刷新，也不使用 Sentinel Nacos 数据源持久化；重启按源码和属性重建规则。只保存仓库 YAML 不会影响已经运行的 Nacos。

Gateway 可选连接 Compose 中的 Dashboard：`SENTINEL_DASHBOARD=127.0.0.1:8858`；控制台在 Docker 中、Gateway 在 Windows 宿主时，可设置 `SENTINEL_CLIENT_IP=host.docker.internal` 便于回连，transport 默认 8719。控制台临时改规则只保存在内存，端口仅用于开发/管理网络。未配置控制台或控制台不可用时，本地保护仍生效。

根目录执行：

```powershell
mvn -f backend/pom.xml -pl gateway,service/ai -am test
mvn -f backend/pom.xml -pl gateway,service/ai -am package -DskipTests
```

Windows 受限测试环境可在本次命令追加匹配版本的 Mockito `-DargLine=-javaagent:...`，以及 `-Dcsp.sentinel.log.dir=E:/Project/cilmbing/tmp/sentinel-test-logs`。真实 HTTP stub 测试需要本机回环连接权限。

## 已验证与限制

- Gateway 当前 43 项测试通过，其中 9 项通过真实 WebFlux HTTP 和 Sentinel 规则验证超限、API 分组、鉴权顺序及业务响应；另验证关闭流控后目标接口可连续放行。
- AI 当前 86 项测试通过，包含真实 Sentinel 引擎的打开、拒绝、半开成功/失败、4xx 排除、禁用和参数校验，以及熔断时不扣额度、不调用模型。
- Gateway 与 AI 的 JAR 构建通过，现有业务契约保留。
- 未启动 Docker、未发布真实 Nacos 配置、未执行数据库迁移；实际部署拓扑和业务容量仍需联调。规则演示值不作为生产 SLO 或性能提升数据。

依赖和规则参考：[官方 Gateway 文档](https://sentinelguard.io/en-us/docs/api-gateway-flow-control.html)、[官方熔断文档](https://sentinelguard.io/zh-cn/docs/circuit-breaking.html)。
