# Prompt for Terra: Java backend template only

Copy the prompt below into a dedicated Terra coding task after this frontend/database contract has been reviewed. It intentionally requests a compilable skeleton and guardrails, not the user's Spring Cloud learning implementation.

---

你正在一个已有仓库中生成 **Java 后端模板代码**。先完整阅读仓库，不要覆盖现有前端、SQL、基础设施或文档。

项目是“AI 手部症状与不适辅助分析系统”的学习型 MVP，场景是攀岩 + 手部不适。它帮助用户表达不舒服的位置、症状表现、诱发动作、时间和是否影响正常使用，并给出是否值得进一步寻求专业帮助的提示；疼痛只是症状之一，它不是医疗诊断系统。

## 唯一契约与已定边界

以这些现有文件为准：

- `docs/api/openapi.yaml`：唯一 HTTP wire contract；
- `docs/architecture.md`：所有权和延后决策；
- `database/base_db/V001__base_schema.sql`；
- `database/image_db/V001__image_schema.sql`；
- `database/history_db/V001__history_schema.sql`；
- `database/future/ai_db/V001__FUTURE_NOT_APPLIED_ai_schema.sql`：只能阅读，严禁自动执行。

四个服务边界已经暂定，不要重划：

1. `base-service`：登录、账号、基础资料；
2. `image-service`：MinIO 图片、元数据、Annotation、未来图片解析；
3. `history-service`：Assessment、症状/不适历史、恢复状态、最终结果；
4. `ai-service`：未来模型和 Agent，目前只保留模板边界。

业务 PostgreSQL 实例含 `base_db`、`image_db`、`history_db` 三个逻辑数据库；AI PostgreSQL 是带 pgvector 的独立实例，含 `ai_db`。禁止跨数据库外键、跨服务 Repository、跨服务 SQL JOIN。

## 本任务只生成什么

创建一个 Java 21 + Maven 的可编译多模块模板，每个服务保持独立包边界。先检查仓库是否已经规定 Spring Boot / Spring Cloud 版本；如果没有，查官方兼容矩阵后选择一组兼容的稳定版本，并在 README 中说明选择，不要盲目使用 `latest`。

为每个服务生成：

- 独立启动类和最小配置；
- 按 OpenAPI ownership 划分的 Controller 接口/薄 Controller；
- 请求/响应 DTO、Bean Validation、枚举和 RFC 9457 `application/problem+json` 异常结构；
- application/domain/infrastructure 的轻量目录骨架；
- application service **接口**，用于我后续填写核心业务；
- 与本服务 SQL 表对应的 persistence record/entity 和 repository **接口或最小适配骨架**；
- 配置属性类和示例配置，不提交密码；
- 最小单元测试、架构边界测试或 context test；
- 模块 README，列出明确的 `TODO(owner: learner)`。

模板必须能通过：

```text
mvn test
```

如果使用 OpenAPI Generator，请把生成代码隔离在 build/generated，并让手写 domain 不依赖生成实现；如果手写 contract DTO，则必须逐项对照 `docs/api/openapi.yaml`。不要擅自更改路径、字段命名、状态码和 service ownership。发现契约矛盾时，记录在 `docs/backend-contract-review.md`，不要静默修改前端或 OpenAPI。

## 明确不要实现

不要替我实现或决定以下内容：

- 核心 Assessment 业务规则；
- 真实微信登录和生产 JWT 安全方案；
- 服务调用方向；
- Gateway 路由；
- Nacos 注册/配置；
- OpenFeign client；
- Sentinel 规则；
- timeout、retry、fallback、熔断；
- RabbitMQ exchange/queue、producer、consumer；
- Outbox、Inbox、Seata、Redis；
- 真实 MinIO 上传副作用；
- 真实 Agent、模型 SDK、Prompt、RAG；
- `ai_db` migration 执行或 Flyway/Liquibase 自动加载；
- 医疗诊断结论。

尤其不要为了“看起来完整”写服务间同步调用或返回伪造成功。未实现的 application service 可用明确的开发期 stub/profile 或 `501 Not Implemented`，但正常启动、验证和错误映射必须清楚、可测试。

## 每个服务的模板重点

### base-service

- 保留 WeChat code 登录的 port/interface，不调用微信网络；
- `CurrentUser` / authentication context 做成可替换边界；
- 账号与基础资料只访问 `base_db`。

### image-service

- 用 `ObjectStoragePort` 表达 put/get/delete/presign 能力，提供测试 fake，不连接真实 MinIO；
- multipart upload、`Picture`、`AnnotationSet` DTO 与 OpenAPI 对齐；
- Annotation 坐标为原图左上角 `[0,1]`，不要把红圈烧进唯一原图；
- 预留 `ImageProcessingPort` 和幂等状态条件，不实现 MQ；
- 重复副作用未来通过 `pictureId`、processing generation、状态条件和唯一约束处理。

### history-service

- 只访问 `history_db`；`user_id`、`picture_id` 是外部业务 ID；
- 提供 Assessment 创建、更新、症状多选、症状位置、视觉引用、动态答案、恢复、提交、历史和结果的接口骨架；
- `SymptomType` 与 `assessment_symptom` 必须支持 `PAIN`、`ACHING`、`DISTENSION`、`NUMBNESS`、`SWELLING`、`WEAKNESS`、`STIFFNESS`、`OTHER_UNCERTAIN`，不得退回单一“疼痛类型”字段；
- 使用 version 做乐观并发；
- 不调用 base/image/ai 服务。

### ai-service

- 只创建空的服务模块、OpenAPI future Controller/application ports/DTO 和清楚的 `FUTURE_NOT_IMPLEMENTED` 标识；
- 禁止引入模型厂商 SDK；
- 禁止启用 future AI SQL；
- 禁止让其他三个服务依赖它才能启动或测试。

## 工程约束

- 不创建“大而全”的 shared-domain 模块。可以有一个极小的技术性 test/util 或 problem-details 模块，但跨服务业务 DTO 各自拥有，避免编译期耦合。
- 不把稳定查询字段塞进 JSON；不要重新设计已有 SQL。
- 时间使用 UTC `Instant`，ID 使用 UUID，金额类问题不存在。
- 日志不输出 token、图片签名 URL、用户原始照片内容、完整 Prompt/Response。
- API 创建类副作用接收 `X-Idempotency-Key`；PUT/update 使用契约中的 version/revision。
- 所有 TODO 要说明“学习者下一步做什么”，不要用大段空洞注释。
- 不修改 `frontend/`、`database/`、`infra/` 和现有设计文档；仅允许新增后端目录、后端专属 README 及契约审查记录。

## 交付前检查

1. 列出新增模块和各自数据库所有权；
2. 运行并修复 `mvn test`；
3. 检查依赖树，不得出现 Redis、Seata、模型 SDK、向量库客户端、Feign、Sentinel、RabbitMQ starter（本模板阶段都不需要）；
4. 搜索并确认没有引用 FUTURE AI SQL 的 migration 配置；
5. 搜索并确认没有跨服务 JPA relationship/repository；
6. 对照 OpenAPI 输出覆盖矩阵：路径、operationId、Controller、实现状态；
7. 最终报告只说明已完成内容、验证命令、仍属于 `TODO(owner: learner)` 的核心学习项和发现的契约问题。

遇到普通模板选择请做合理决定并记录，不要频繁询问。只有现有文件之间存在会改变服务边界、数据所有权或公开 API 的真实冲突时才停止请求确认。

---
