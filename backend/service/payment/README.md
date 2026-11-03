# payment-service

独立的 Spring MVC 服务，监听 `8085`，仅拥有业务 PostgreSQL 实例上的 `payment_db`。不调用 Base，不访问其他服务数据库，不使用 Seata、消息队列或远程支付。当前仅提供开发用模拟确认；正式支付渠道尚未接入。

先人工创建 `payment_db` 并审阅、执行 [V001 SQL](../../../../database/payment_db/V001__payment_schema.sql)。应用不会自动运行建表或迁移脚本。将 `infra/nacos-config/payment-service-dev.yaml` 的项目配置样例导入 Nacos `CLIMBING_DEV` 组，设置实际 datasource 参数。应用导入 `payment-service-dev.yaml`；Nacos 地址可通过 `NACOS_SERVER_ADDR` 覆盖。

在仓库根目录执行：

```powershell
mvn -f backend/pom.xml -pl service/payment -am package -DskipTests
java -jar backend/service/payment/target/payment-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

`payment.mock-enabled` 在应用内默认 `false`。模拟确认必须同时显式启用该配置并激活 `dev` profile；无 `dev` 或存在 `prod` / `production` profile 时始终返回 403。不要向生产配置启用模拟支付。服务本身信任 `X-User-Id` UUID，只能放在能校验身份、清除并重写该请求头的网关后方；`/internal/payment/entitlement` 不应暴露到公共网关。

公共 API 前缀是 `/api/payment/v1`：

| 方法和路径 | 行为 |
| --- | --- |
| `GET /plans` | 可购买套餐数组；种子仅 PLUS，1990 分 CNY，30 天，每日 50 次 |
| `POST /orders` | `X-User-Id`、UUID `X-Idempotency-Key`，JSON 仅 `{ "planCode": "PLUS" }`；返回 201 |
| `GET /orders?limit=20` | 当前用户最近订单数组，limit 为 1..50 |
| `GET /orders/{orderId}` | 当前用户的订单；不存在或归属他人统一 404 |
| `POST /orders/{orderId}/mock-pay` | 无请求体；开发环境确认，返回订单 |
| `GET /entitlement` | 当前用户的有效权益；未购买或到期返回 FREE / 免费 / 10 / null |

内部 `GET /internal/payment/entitlement` 使用相同 `X-User-Id`、无请求体，返回相同权益 DTO。接口时间字段输出 UTC Instant，金额为整数分，客户端不能决定购买价格、天数或调用额度。

订单在创建时保存套餐快照，30 分钟后未支付的订单确认返回 409，状态仍为 PENDING。`(user_id, idempotency_key)` 的唯一约束与 `INSERT ... ON CONFLICT DO NOTHING` 保证数据库级幂等；同键同套餐重放既有订单，同键不同套餐返回 409。旧订单重放不受当前套餐上下架影响。

确认先锁定当前用户订单，已 PAID 直接返回，再通过权益行 `INSERT ... ON CONFLICT` 与 `SELECT ... FOR UPDATE` 串行处理同用户的不同订单。有效期在 `max(now, valid_until)` 上增加订单快照天数。权益写入和 PAID 标记属于同一本地事务；任一步失败整体回滚。没有 EXPIRED 状态，也不会在一个将回滚的事务里伪更新过期状态。FREE 是虚拟默认值，不需要权益行。

单元测试通过固定时钟与 Mapper SQL 参数检查覆盖订单重放/冲突、确认重放、权益续期、用户归属和环境开关；不连接数据库、Docker 或模型服务。这些测试不代替 PostgreSQL 上的真实并发与回滚集成验证。
