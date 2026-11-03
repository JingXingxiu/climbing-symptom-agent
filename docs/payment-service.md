# 模拟套餐购买与 AI 每日额度

新增独立 `payment-service`，端口 `8085`，使用现有业务 PostgreSQL 实例中的 `payment_db`。不新增 PostgreSQL Server，不连接真实支付渠道。前端首页提供“套餐与额度”入口，默认 Mock 模式可完整体验购买和额度变化。

## 数据所有权

| 服务 | 数据 |
| --- | --- |
| Base | 登录身份、账号、基础资料 |
| Payment | 可购买套餐、订单、当前套餐权益和有效期 |
| AI | 每个用户当天的执行用量、原有 Session 与模型调用记录 |

Payment 不修改 Base 的用户表。AI 通过 OpenFeign 查询 Payment 权益，不直接访问 `payment_db`。跨服务只保存业务 UUID，没有跨库外键。

## 当前规则

- FREE：每日 10 次模型执行尝试，无需购买。
- PLUS：演示价格 1990 分，购买后 30 天内每日 50 次。
- 每个 Agent Session 原有的最多 20 次执行尝试、40 个步骤限制继续保留。
- 日期按 `Asia/Shanghai` 计算，零点使用新的日期行，无需定时清零。
- 续购在 `max(当前时间, 当前有效期)` 上延长；当天已用次数不清零。
- 每次 `advanceSession` 预占一个执行名额。预占事务回滚不计次；事务提交后，即使图片读取失败、模型请求失败或旧响应被丢弃，也保留该次计数。
- 创建/恢复 Session、保存回答、查询记录和查询额度本身不计次。

订单创建时冻结套餐名称、整数分金额、天数和每日限额。客户端只传 `planCode`，不能自行指定价格或权益。

## 事务与幂等

建单通过 `(user_id, idempotency_key)` 唯一约束和 `ON CONFLICT DO NOTHING` 实现幂等。同一业务意图重试复用 UUID；同键换套餐返回 409。

确认支付先锁定当前用户的订单。已支付直接返回；待支付订单必须仍在创建后 30 分钟内。随后创建或锁定用户权益行，串行处理同用户的不同订单。订单变为 PAID 与权益续期在 Payment 同一个本地事务中提交，失败一起回滚。

AI 在事务外查询权益，再在现有执行预占事务内通过单条条件 upsert 增加当日用量。这与 Session/ModelCall 写入属于同一个 AI 本地事务；跨 Session 和多实例都竞争同一用户日期行。Payment 查询失败返回 503，不允许无限额度；额度耗尽返回 `429 AI_DAILY_QUOTA_EXCEEDED`。普通限流的 429 只提示重试，不引导购买套餐。

这条流程没有跨库同时写入的事务，不使用 Seata、Outbox、Inbox 或 MQ。

## 启动顺序

1. 恢复 Docker Desktop 后启动现有基础设施组。
2. 创建并应用 Payment 数据库脚本。
3. 将六份 Nacos 开发配置发布到 `CLIMBING_DEV`。
4. 启动 Gateway、Base、Image、History、Payment。AI 库完成下述人工审查迁移后，再启用 AI 运行。
5. 前端使用默认 Mock，或设置 `VITE_API_MODE=http` 后重新构建，使用 Gateway 和有效 JWT 联调。

已有 Docker 卷不会重新执行初始化脚本。在 `infra/` 执行以下命令，数据库已存在时跳过创建；V001 只对空库执行一次：

```powershell
docker compose exec -T business-postgres psql -U symptom_app -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE payment_db;"
docker compose exec -T business-postgres psql -U symptom_app -d payment_db -v ON_ERROR_STOP=1 -f /workspace/database/payment_db/V001__payment_schema.sql
.\nacos-config\publish-config.ps1
```

使用自定义 PostgreSQL 用户时替换示例用户名。应用不自动建库、建表或迁移。

从项目根目录构建并启动 Payment：

```powershell
mvn -f backend/pom.xml -pl service/payment -am package
java -jar backend/service/payment/target/payment-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --payment.mock-enabled=true
```

应用内 `payment.mock-enabled` 默认 false。模拟确认同时要求显式 `dev` profile 和开关 true；出现 `prod` 或 `production` profile 时始终禁止，即使同时启用了 dev。该入口不代表真实付款成功，也不能暴露为真实支付回调。

Nacos 新增 `payment-service-dev.yaml`，Gateway 增加 `/api/payment/**` 到 `lb://payment-service` 的路由，保留完整路径。AI 新增 `agentPaymentClient` 超时配置。`/internal/payment/**` 只供受限服务网络调用，不经 Gateway 路由。只修改仓库样例不会更新已经运行的 Nacos。

## AI SQL 保持 FUTURE / NOT APPLIED

以下 SQL 本轮未执行，需审查后显式启用：

1. `database/future/ai_db/V001__FUTURE_NOT_APPLIED_ai_schema.sql`
2. `database/future/ai_db/V002__FUTURE_NOT_APPLIED_user_daily_usage.sql`
3. `database/history_db/V003__assessment_result_revision.sql`（结果定稿需要）

V002 仅增加 `ai_user_daily_usage`；它不依赖 RAG 或 embedding。Payment 和 AI 库仍在两个不同 PostgreSQL 实例上，各自拥有本地事务。

## 前端行为

套餐页展示当前权益、上海日期的用量、可购买套餐、订单金额和最近订单。建单响应不确定时持久化原幂等键并复用；模拟支付响应不确定时重新 GET 原订单确认，不自行标为已支付。明确被拒绝的建单请求允许重新发起购买意图。

Agent 页面展示额度，真正耗尽时提供套餐入口。返回页面只读取已有状态，不自动重发被拒绝的分析。HTTP 模式失败不会退回 Mock；Mock 模式的订单、权益和执行计数独立持久化，用于离线演示。

## 验证入口

```powershell
# backend/：支付、额度和原 Agent 回归
mvn -o -pl service/payment,service/ai -am test

# frontend/：幂等、续期、到期、切日、计数、HTTP 边界
pnpm run test:payment
pnpm run type-check
pnpm run build:mp-weixin
```

Windows 受限环境若禁止 Mockito 动态 attach，可仅在本次 Maven 命令用 `-DargLine=-javaagent:本机/mockito-core-对应版本.jar`；不需要修改生产配置。HTTP stub 测试还需要本机回环连接权限。

本轮验证通过：AI 62 项、Payment 31 项离线测试，两个服务打包，前端支付运行时断言、TypeScript 检查和标准微信小程序构建。OpenAPI 的全部引用、四个新增响应 DTO 字段、六份 Nacos YAML 和 Compose 配置也已检查。

当前 Docker 被手动暂停，未恢复或修改现有数据库。真实 PostgreSQL 行锁、跨实例并发、事务回滚、Gateway 到各服务以及 AI 到 Payment 的部署联调仍需在环境恢复后验证，离线 Mapper 测试不替代这些验证。

完整字段与接口仍以 [OpenAPI](api/openapi.yaml) 为准，不在此重复维护接口清单。
