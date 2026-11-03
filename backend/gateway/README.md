# gateway

Gateway 通过 Nacos 发现服务，统一完成外部请求的 JWT 鉴权和身份传递。
真实部署、Nacos 配置和跨服务链路仍需联调验证。

## 路由与配置

路由集中保存在 `infra/nacos-config/gateway-dev.yaml`，作为 Nacos `CLIMBING_DEV` 配置的 seed：

| Path | 目标 |
| --- | --- |
| `/api/base/**` | `lb://base-service` |
| `/api/image/**` | `lb://image-service` |
| `/api/history/**` | `lb://history-service` |
| `/api/ai/**` | `lb://ai-service` |
| `/api/payment/**` | `lb://payment-service` |

现有 Path 已覆盖公开 API 前缀；鉴权由统一过滤器处理，不需要为每个接口重复配置路由规则。
后端 Mapping 保留完整 `/api/...` 前缀，因此不使用 StripPrefix。没有内部端点路由。
HTTP 客户端连接超时为 3 秒，默认响应超时为 10 秒，AI 路由单独为 150 秒，不自动重试。
Sentinel 的本地 API 分组流控见下文；不自动重试或伪造业务成功响应。

先导入或更新上述 Nacos seed，再重启 Gateway：当前 `refreshEnabled=false`，配置不会动态刷新。
本地 `application.yaml` 保留 Nacos 入口、鉴权和 Sentinel 环境变量配置，路由不再复制一份。
seed 允许来自 `http://localhost:18081`、`http://127.0.0.1:18081` 的 Swagger UI 跨域请求，
支持 `Authorization`、`Content-Type`、`X-Idempotency-Key` 等请求头。

## 统一鉴权

`GlobalFilter` 顺序为 `-100`。只有精确匹配 `POST /api/base/v1/auth/wechat-login` 的请求公开；
其余匹配路由的请求须提供 `Authorization: Bearer <JWT>`。
使用 Nimbus JOSE JWT 10.5，固定 HS256，校验签名、`iss`、`aud`、`exp`、`iat`、
可选 `nbf`，并要求 `sub` 为用户 UUID。

| 配置 | 值 |
| --- | --- |
| `gateway.auth.secret` | `${BASE_JWT_SECRET:}`，至少 32 字节的随机密钥 |
| `gateway.auth.issuer` | `climbing-base-service` |
| `gateway.auth.audience` | `climbing-api` |
| `gateway.auth.clock-skew` | `30s` |

密钥、issuer 和 audience 必须与 Base 一致。IDEA 的 Gateway Run Configuration 中设置
`BASE_JWT_SECRET`，与 Base 使用相同值；不要将真实密钥写入 Nacos seed 或 Git。

转发前清除客户端传入的全部 `X-User-Id`；验签成功后从 `sub` 注入可信 `X-User-Id`。
缺少或无效的令牌返回 `401 application/problem+json`，并带 `WWW-Authenticate: Bearer`；
鉴权配置缺失时，受保护请求返回 503。

JWT 当前没有撤销机制，也不会即时同步账号禁用状态。Base 在访问当前用户时会检查账号状态，
History 不查询 Base。部署时应只允许内部调用访问服务端口，防止绕过 Gateway；
当前学习环境尚未部署这层网络隔离，不能据此宣称完整部署安全已经完成。

## Sentinel 网关流控

使用 Alibaba BOM 管理的官方 `sentinel-spring-cloud-gateway-v6x-adapter:1.8.9`，
以及同版本的 `sentinel-transport-simple-http`。规则在 `GatewaySentinelConfiguration` 中加载，
通过 `gateway.sentinel` 的配置属性调整阈值；重启后可复现，不需要先在控制台手工添加规则。
未引入 Sentinel Nacos 数据源或集群流控。

| API 分组 | 仅保护的请求 | 演示默认值 |
| --- | --- | --- |
| `gateway-agent-advance` | `POST /api/ai/v1/agent-sessions/{sessionId}/advance` | 单实例合计 2 QPS |
| `gateway-image-upload` | `POST /api/image/v1/pictures` | 单实例合计 5 QPS |

不同 Session 的 advance 共享同一 API 分组，图片上传使用独立分组。规则窗口为 1 秒，
超限立即拒绝，不排队、无额外 burst。这里的阈值只是可调演示默认值，尚未通过容量压测；
多实例各自计数，不代表全局总额度，也不替代 AI 服务按用户和业务日期计算的每日额度。
其他路径、GET/OPTIONS 等方法不计入这两个分组。

Java 属性和本地 `application.yaml` 默认关闭；仓库的开发 Nacos seed 显式演示启用，
导入新版 seed 后会覆盖本地默认值。设置 `GATEWAY_SENTINEL_ENABLED=false` 可关闭。
也可在 Gateway 的运行环境显式启用，不需要启动控制台：

~~~powershell
$env:GATEWAY_SENTINEL_ENABLED = "true"
$env:GATEWAY_AGENT_ADVANCE_QPS = "2"
$env:GATEWAY_IMAGE_UPLOAD_QPS = "5"
~~~

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `gateway.sentinel.enabled` | `GATEWAY_SENTINEL_ENABLED` | `false` |
| `gateway.sentinel.agent-advance-qps` | `GATEWAY_AGENT_ADVANCE_QPS` | `2`，正整数 |
| `gateway.sentinel.image-upload-qps` | `GATEWAY_IMAGE_UPLOAD_QPS` | `5`，正整数 |
| `gateway.sentinel.dashboard` | `SENTINEL_DASHBOARD` | 空，不发送控制台心跳 |
| `gateway.sentinel.transport-port` | `SENTINEL_TRANSPORT_PORT` | `8719` |
| `gateway.sentinel.client-ip` | `SENTINEL_CLIENT_IP` | 空，由 Sentinel 选择本机地址 |

表中是 Java/本地默认值；开发 seed 另设置了控制台和 Docker 回连地址。
可选控制台连接：宿主机运行 Gateway 时，将 `SENTINEL_DASHBOARD` 设为控制台的宿主机地址，
例如 compose 默认的 `127.0.0.1:8858`。若控制台在 Docker 内，`SENTINEL_CLIENT_IP=host.docker.internal`
可让控制台回连宿主机的 Sentinel transport 端口；实际端口以 Docker 配置和本机监听为准。
控制台按需显示请求指标，未配置或控制台不可用时，本地规则仍执行。
transport 是 Sentinel 的管理端口，不是业务路由，应只在开发/管理网络使用；
端口冲突时 Sentinel 可能自动选择后续可用端口。控制台临时修改只存在于内存，重启会恢复源码配置规则。

官方 API predicate 只匹配路径，因此 `PostOnlySentinelGatewayFilter` 仅为 POST 调用官方 adapter。
其顺序为 `-90`，在 JWT 过滤器 `-100` 之后；没有基于外部 `X-User-Id` 的限流参数。
未鉴权请求仍返回原来的 401/503，内部端点仍没有路由。被限流的请求不会到达下游，返回
`429 application/problem+json`，包含 RFC 9457 的 `type/title/status/detail/instance`、
`code=GATEWAY_RATE_LIMITED` 和 `Retry-After: 1`。
下游返回的业务状态、错误码和 payload 保持原样，例如 `AI_DAILY_QUOTA_EXCEEDED`。

该版本 adapter 的上游构建目标为 Spring 6 / Gateway 4；本项目已在 Java 21、Boot 4.0.6、
Gateway 5.0.0 和 Jackson 3.1.2 的真实 WebFlux 上下文与 HTTP 请求下验证本节使用的功能。
参考 [Sentinel 官方网关文档](https://sentinelguard.io/en-us/docs/api-gateway-flow-control.html)
和 [1.8.9 官方 adapter 源码](https://github.com/alibaba/Sentinel/tree/1.8.9/sentinel-adapter/sentinel-spring-cloud-gateway-v6x-adapter)。

## 检查

在 `backend` 目录运行：

~~~powershell
mvn -pl gateway -am test
~~~

本机已执行：43 项测试通过，其中 15 项校验 JWT，13 项验证默认关闭流控时的 HTTP 路由、身份头、
鉴权错误、CORS 和目标 API 放行；9 项在启用真实 Sentinel 规则的 Gateway 上验证 API 分组、
QPS 超限 429、Problem 响应、身份头不能绕过鉴权、非目标 API 不计数以及下游业务 429 保留；
6 项验证配置默认值、禁用和非法阈值/端口拒绝。
测试只连接本机 HTTP stub，隔离了 Nacos、数据库、真实模型和控制台，不代表真实部署链路或容量压测已验证。
