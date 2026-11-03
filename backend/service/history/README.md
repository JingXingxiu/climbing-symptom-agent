# history-service

管理 `history_db` 中的 Assessment、症状、位置、图片关联、问答和最终业务结果。
保持 MVC；关联外部服务只保存业务 ID，不访问其他服务的数据库，也不建立跨库外键。

AssessmentService 的 11 个现有方法均已有非 AI 业务实现。
结果接口只读取已经持久化的最终结果，不生成结果；真实 Agent、模型调用及结果生成/入库编排仍未实现。

## 本轮阅读顺序：四个 Service 方法

| 方法 | 输入与读取 | 写入 | 返回 |
| --- | --- | --- | --- |
| `resume` | 当前用户、Assessment ID → 查询根记录及症状/位置/图片关联/答案 | 不写库 | 完整记录、恢复步骤；当前无真实会话，`activeAgentSessionId=null` |
| `submit` | 当前用户、Assessment ID、请求 `version` → 锁根记录，检查必要输入与位置/图片引用 | 首次/有新修改时保存提交凭据、版本+1、步骤 AGENT；不标记完成 | 更新后的完整 Assessment；幂等重放不写库 |
| `appendAnswer` | 当前用户、Assessment ID、`clientAnswerId`、序号、题目和 JSON → 锁根并查询重复键/序号 | 新答案写 `assessment_answer`，同时更新根版本和保存时间 | 原有或新建的答案，不决定下一问 |
| `getResult` | 当前用户、Assessment ID → 确认归属及 COMPLETED，读取 `assessment_result` | 不写库 | 保留原始来源和安全声明的结果；尚未完成/无结果返回 404 |

`assessmentId` 标识一次业务记录，`clientAnswerId` 标识一次回答意图，`sequenceNumber` 仅表达顺序，
不是幂等键；同 Assessment 的序号必须唯一但允许间隔。相同回答键和相同完整内容（JSON 结构比较）
返回已存答案；同键改内容或不同键抢占序号返回 409。有效重放即使在完成后也不会追加或更新时间。
`source` 只是调用方声明的来源标签，不是已经执行真实 AI 或可信模型审计的证明。
新增答案必须已有成功提交凭据（`submitted_at`、`last_submit_version`），草稿或尚未提交的
COLLECTING 记录返回 409 `ASSESSMENT_NOT_SUBMITTED`；追加答案只更新版本和时间，保留原状态。
已有答案的有效幂等重放先于这些新建限制判断，不要求重新提交。

提交只要求精简必要信息：左右手、开始时间表达、诱发动作/情况、正常使用影响，
以及至少一条粗定位或一张关联图片；症状类型仍可选，不强制收集所有医疗问题。
相对时间需正数；明确日期需非空且不晚于 MVP 使用的 Asia/Shanghai 当天。
提交不会调用 Image/AI、不产生会话、不生成结果，不填写 `completed_at`。
DRAFT 提交后变为 COLLECTING，已有 COLLECTING/CLARIFYING 保留，步骤进入 AGENT；
取消记录不能提交或继续填写，完成记录的恢复视图进入 RESULT。

### 为什么增加两个内部提交字段

前端可以先保存 `currentStepKey=AGENT`，所以页面导航不能作为提交成功凭据。
`submitted_at` 保存最近接受提交的时间，`last_submit_version` 保存该次请求提交前的版本。
它们不进入现有公开 DTO，不新增状态枚举，也不会从旧 AGENT 步骤推断或回填。

例如版本 3 首次提交成功后变成 4，凭据记录 3：重试 3，或未发生任何修改时读取 4 再提交 4，
都直接返回当前记录。若业务后来已修改为版本 5，可用 5 重新校验提交，结果变成 6、凭据更新为 5。
只保留最近一次提交凭据，更早且不再匹配的旧版本返回 409，不构建事件溯源表。
提交和答案操作在事务内先锁当前用户的根记录，保证同 Assessment 的幂等检查与写入串行；
`version` 仍负责识别客户端陈旧状态。数据库唯一约束继续兜底，不在 PostgreSQL 异常后继续查询。
位置替换/清空也会原子递增根版本，不能只更新时间，否则提交会误判为“未修改”。
位置接口仍返回原有结构；HTTP 前端在保存位置后读取最新根版本，再保存图片关联。

HTTP 前端启动问答时会先提交并刷新版本，再调用现有 AI 接口；AI 当前仍返回 FUTURE/501。
提交成功不等于 Agent 已启动，前端本地 Mock 演示也不代表真实后端问答闭环。

## 第一条跨服务调用：replaceVisuals

1. 查询当前用户的 Assessment，校验可编辑状态、版本及完整图片 ID 列表（最多 12 张，不能重复或含 null）。
2. `ImageClient` 按注册名 `image-service` 一次 POST 到 `/internal/image/visual-references/resolve`，
   不经过 Gateway。显式传 `X-User-Id` 和 Assessment/完整图片列表；空列表不发远程请求。
3. Image 一次批量查询可访问图片、一次查询当前用户/Assessment 的标注集；
   必须全部可访问且 `AVAILABLE`，缺标注合法返回 null。History 检查响应数量、ID 完整性、
   非空来源和重复项，并按原请求顺序保存。404/409/503/502 失败不会伪装成成功。
4. Feign 调用结束后，使用 `assessmentWriteTransaction` 开启独立的本地短事务，
   按 `id + user_id + version + status` 条件更新根记录、版本加一并保存时间，随后整组替换图片关联。
   任一写入失败，版本和关联一起回滚。空图片列表表示清空关联，不删除 Image 中的图片或标注。

`ImageClient` 只定义 History 消费的最小响应 records，并忽略其他 JSON 字段；
没有导入 Image 的 Java 模块、Entity 或 DTO。OpenFeign、LoadBalancer 已从父 POM 继承。
旧的两个单图 GET 保留给前端契约回归与性能基线，生产 `replaceVisuals` 不再逐图调用。
没有额外实现 Sentinel、超时策略、自动重试、fallback、MQ 或分布式事务。
`application.yaml` 注册了本服务的 `UuidTypeHandler`、`PgObjectTypeHandler`，让自定义 INSERT 与
MyBatis-Plus 生成 SQL 能绑定 PostgreSQL 原生 UUID/JSONB；不依赖 Image 的实现类。

## 联调前提与边界

- History 和 Image 使用相同的 Nacos 地址与 `CLIMBING_DEV` 分组；Image 注册名保持 `image-service`。
- 业务库先应用各自已有 SQL；Image 的标注接口还需要其 `V002__annotation_source_dimensions.sql`。
  History 新库按 V001 → `V002__assessment_submission.sql` 执行；已执行 V001 的库只补 V002，
  不重建业务表。SQL 不会随服务启动自动执行；在其他环境从仓库 `infra/` 手动执行：

  ```powershell
  docker compose exec -T business-postgres psql -U symptom_app -d history_db -v ON_ERROR_STOP=1 -f /workspace/database/history_db/V002__assessment_submission.sql
  ```

  本机执行记录（2026-10-25）：确认 `history_db` 原为空库后，已依次应用 V001、V002。
  六张业务表、两个提交字段和有效 CHECK 约束均已验证；UUID/JSONB 与提交约束冒烟测试通过，
  测试事务已回滚，没有留下测试记录。业务 PostgreSQL 容器当前健康运行，其他库未在此轮迁移。
- `X-User-Id` 在正式环境必须来自可信 Gateway/内部身份链路，不应信任任意外部客户端传头。
  Base 登录与完整认证链路仍由后续学习实现。
- 这里只保证 History 本地事务；远程校验后图片仍可能被另一个请求删除，不承诺跨服务原子性。
- 标注可晚于图片关联创建。前端保存标注后会再次同步关联以补齐 `annotationSetId`；
  History 返回图片关联列表而不返回新版本，因此同步成功后 GET Assessment 刷新版本和缓存。

## 审查入口与验证

依次查看 `AssessmentServiceImpl.replaceVisuals`、`ImageClient`、
`AssessmentTransactionConfiguration`、`AssessmentVisual.create`、`VisualReference.fromEntity`。
原有 Controller 和 OpenAPI 请求/响应形状未改变。

从 `backend/` 运行隔离测试（本机 HTTP stub，不需要运行中的 Nacos、PostgreSQL 或 MinIO）：

```powershell
mvn -o -pl service/history -am "-Dtest=*Test,HistoryIdempotencyContractTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Service 单测验证条件更新、事务边界和 rollback 信号；HTTP 测试验证真实 OpenFeign 编解码及路径/身份头。
这些不能替代真实 PostgreSQL 回滚、Nacos 注册发现和微信端到端联调。

批量优化的候选项目亮点、实测口径和复跑方法见仓库 `docs/project-highlights.md`。
性能微基准默认跳过，显式启用：

```powershell
mvn -o -pl service/history -am "-Dtest=VisualReferenceBatchBenchmarkTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dvisual.benchmark=true" test
```

输出 `service/history/target/benchmark/visual-reference-batch.json` 和 `visual-reference-batch-samples.csv`。
该基准是真实 Feign/HTTP 调用加模拟服务器，不包含实际 Image SQL、Nacos、MinIO 或 History 写库。
