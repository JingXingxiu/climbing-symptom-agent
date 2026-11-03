# 症状/不适辅助分析系统：后端开发主线

> 这是当前唯一的开发顺序文档。旧说明资料已归档到 `docs/history/2026-09-29-pre-development/`，日常开发不需要读取。

本文只说明开发顺序、分工和验收标准，不重复抄写完整 API、字段或 DTO。HTTP 契约统一以 `docs/api/openapi.yaml` 为准。

当前已进入 Agent 学习阶段，并按你的要求补齐 Service 参考实现。先读 [Agent 数据库说明](agent-database-learning.md)，再按 [Agent Service 阅读顺序](agent-service-reading.md) 逐个理解和复现。下面的分工与阶段顺序保留为此前学习路线；当前请求优先，SQL仍未执行。

## 1. 总原则

本项目采用普通 Spring MVC 分层：

```text
Swagger / 前端
    -> Gateway
    -> Controller
    -> Service
    -> Mapper
    -> PostgreSQL
```

- 不做完整 DDD，不引入不必要的 Command、Port、Adapter、Aggregate 仪式。
- 不按 OpenAPI 文件中的接口排列顺序批量写代码；每次完成一条可运行的业务链路。
- OpenAPI 定义 HTTP 契约，Service 接口定义 Java 内部的业务能力，两者不是同一个东西。
- Controller 只做参数接收、校验、获取当前用户和调用 Service。
- 事务、状态变化、所有权、幂等和业务判断全部放在 Service。
- Mapper 只负责数据访问，不做业务决策。
- Entity 不直接返回给前端；Controller 不直接调用 Mapper 或 MyBatis-Plus 的通用 `IService`。
- 各服务只访问自己的数据库，不建立跨库外键。

接口模型统一使用以下命名规则：

- Entity 保留业务名称；HTTP 根请求使用 `...Request`，HTTP 根响应使用 `...Response`。
- `Result`、`Outcome`、`Page`、`Summary` 表达业务含义或数据形状，不代替 `Response`；例如最终结论的响应类型是 `AssessmentResultResponse`，实体仍是 `AssessmentResult`。
- 请求与响应共用的嵌套值对象、枚举使用自然业务名称，不机械追加 `DTO`、`Request` 或 `Response`。
- 前端领域模型继续使用 `Assessment`、`AssessmentResult` 等业务名称，只有 HTTP 适配层使用与 OpenAPI 一致的请求、响应类型。

## 2. 哪些由你完成，哪些交给 AI

### 你必须亲自完成

- 选择一条业务链路，并解释它从请求到数据库的全过程。
- 设计 Service 方法名称、参数、返回值和异常语义。
- 决定状态变化、事务边界、用户所有权、幂等规则和跨服务失败时的行为。
- 亲自完成第一条核心链路的 ServiceImpl；后续核心业务仍由你主导。
- 先写出测试场景，再让 AI 补测试代码。
- 亲自学习并接入 Gateway、OpenFeign、Sentinel、RabbitMQ 和后续 Agent。

### 可以交给 AI

- 根据已确认的 SQL 和 Service 需求生成 Entity、Mapper、分页查询等机械代码。
- 补 Controller 到 Service 的委托、DTO 转换和参数校验。
- 生成 MinIO、RabbitMQ、配置类的基础接线代码，但关键策略由你决定。
- 根据你列出的测试场景生成测试脚手架和重复测试代码。
- 做编译修复、契约覆盖检查和代码审查。

### 不要交给 AI 一次性决定

- 整套 Service 接口。
- Assessment 状态机。
- 重试、幂等、事务和补偿规则。
- 服务调用关系和 Agent 决策流程。

## 3. 每个纵向切片的固定开发动作

每次只选 1～3 个紧密相关的 API，然后严格按以下顺序开发：

1. 只看本切片对应的 OpenAPI、DTO、Controller 和数据库表，不通读全项目文档。
2. 用几句话写清正常流程、失败流程、状态变化、权限、事务和幂等。
3. 亲自写 Service 接口。
4. 列出测试场景；至少包含正常、参数错误、资源不存在、越权和重复请求。
5. 让 AI 生成或补齐 Entity、Mapper、Controller 委托及测试骨架。
6. 亲自实现 ServiceImpl 的业务规则。
7. 先跑单元/集成测试，再通过 Swagger UI 经 Gateway 手工验证。
8. 当前切片完全通过后，再进入下一条链路。

一个切片只有同时满足以下条件才算完成：

- 请求确实经过 Gateway、Controller、Service 和 Mapper；
- 数据真实写入或读取对应 PostgreSQL；
- 错误使用统一 Problem 响应，不返回伪成功；
- 核心规则有测试；
- 你能不看代码解释这一链路的事务、权限和幂等处理。

## 4. 正式开发顺序

### 阶段 0：只跑通最小基础设施

目标不是先学完所有中间件，而是为第一条业务链路提供入口。

1. 启动统一 Docker 组：

   ```powershell
   docker compose -f infra/docker-compose.yml up -d --build
   docker compose -f infra/docker-compose.yml ps
   ```

2. 启动 Nacos、`history-service` 和 Gateway。
3. 在 Nacos 控制台确认服务注册。
4. 经 Gateway 调用一个健康检查或最简单的模板接口。

当前阶段不要实现 Feign、Sentinel 规则、MQ 流程或完整鉴权。开发期间先使用固定的开发用户身份，避免微信登录阻塞核心业务学习。

验收：Gateway 能找到 `history-service`，服务启动无配置错误。Nacos 控制台为 `http://localhost:18080/index.html`，Swagger UI 为 `http://localhost:18081`。

### 阶段 1：第一条纵向链路——创建、查询、恢复 Assessment

先在 `history-service` 完成：

1. 创建 Assessment；
2. 根据 ID 查询 Assessment；
3. 恢复未完成的 Assessment。

建议的 Service 形状如下，实际 DTO 名称以 OpenAPI 现有定义为准：

```java
public interface AssessmentService {
    AssessmentResponse createAssessment(
            UUID currentUserId,
            String idempotencyKey,
            CreateAssessmentRequest request);

    AssessmentResponse getAssessment(
            UUID currentUserId,
            UUID assessmentId);

    AssessmentResumeResponse resumeAssessment(
            UUID currentUserId,
            UUID assessmentId);
}
```

第一条链路采用这些简单规则：

- 新建记录初始状态为 `DRAFT`；
- Assessment 与首次症状选择在同一事务中保存；
- 创建请求按“当前用户 + 幂等键”去重，重复请求返回同一资源；
- 用户只能访问自己的记录；为避免泄露他人资源是否存在，越权查询按未找到处理；
- 已提交或已完成的记录可以查询，但不能再按草稿更新；
- Service 接收由认证上下文得到的 `currentUserId`，不能信任请求体传入的用户 ID。

你先亲自写接口、规则和 ServiceImpl。AI 可以补 Entity、Mapper、Controller 委托以及测试骨架。

验收路径：

```text
Swagger -> Gateway -> HistoryController
        -> AssessmentService -> AssessmentMapper
        -> history_db
```

### 阶段 2：纵向扩展 history-service

按以下顺序逐块增加，每一块都重复“规则—Service—测试—实现—Swagger 验收”的循环：

1. 修改基础信息和多选症状；
2. 保存、替换、清除 `SymptomLocation`；
3. 保存动态补充回答；
4. 查询用户历史列表和详情；
5. 关联视觉资料 ID；
6. 提交 Assessment；
7. 查询最终结果。

这里不要按数据库表做 `AssessmentTableService`、`LocationTableService`。Service 应表达业务动作；Mapper 可以按表拆分。

提交动作是本阶段最重要的一步。你需要亲自决定并实现：必填信息检查、状态是否允许提交、重复提交行为、版本冲突以及提交与结果生成是否在同一事务中。

### 阶段 3：完成 image-service 的同步主链路

先不接 RabbitMQ，先让图片能力同步可用：

1. 上传原图到 MinIO；
2. 在 `image_db` 保存对象元数据；
3. 查询图片元数据和访问地址；
4. 保存、查询、修改 Annotation；
5. 支持 Assessment 只保存外部 `imageId`，不建立跨库外键。

三类视觉来源统一为 `Picture / Visual Source + Annotation`：

- 3D 手部固定视角快照；
- 系统标准手部图片；
- 用户上传图片。

Annotation 使用归一化坐标，原图保持不变。Service 负责对象状态、所有权和重复上传规则；MinIO SDK 调用放在独立组件中，不写进 Controller。

验收：三种来源都能形成图片记录和 Annotation；历史服务只持有业务 ID；删除或越权访问不会暴露 MinIO 内部路径。

### 阶段 4：完成 base-service 和真实用户身份

开发顺序：

1. 当前用户资料查询；
2. 用户资料修改；
3. 微信登录；
4. token 签发与校验；
5. 用真实用户上下文替换开发固定用户。

登录成功后再统一处理 Gateway 身份校验和用户信息向下游传递。业务请求体仍然不接受可伪造的 `userId`。

验收：用户 A 无法读取或修改用户 B 的 Assessment、图片或 Annotation。

### 阶段 5：学习并接入服务间调用

单服务链路稳定后，再逐项实现：

1. Gateway 路由和认证透传；
2. history-service 对 image-service 的必要校验调用；
3. OpenFeign；
4. 明确连接超时和读取超时；
5. 只对安全或已幂等的调用考虑重试；
6. Sentinel 限流、熔断与降级。

不要一次同时打开重试、熔断和降级。每增加一种机制，都设计一个可以主动制造的失败场景并观察结果。

验收：正常调用、目标服务超时、目标服务不可用三种情况都有明确结果，不出现无限等待或伪成功。

### 阶段 6：RabbitMQ 图片异步处理

在同步上传已经可靠后再加入：

1. 上传成功后发布图片处理消息；
2. Consumer 执行标准化、resize、thumbnail 和 EXIF 清理；
3. 根据图片业务 ID 与处理状态实现简单幂等；
4. 重复消息不会重复制造错误副作用；
5. 处理失败可被看见并允许手工重试。

当前不实现 Outbox、Inbox、复杂补偿或强可靠消息链。

### 阶段 7：前端从 Mock 切换到真实接口

切换顺序：

1. Assessment 创建、查询和恢复；
2. 基础信息、症状和位置；
3. 标准图片 Annotation；
4. 用户图片上传与 Annotation；
5. 3D 快照与 Annotation；
6. 历史列表、提交和结果；
7. 登录与用户资料。

每次只替换一个 Mock API，实现失败后可以单独回退。前端继续只依赖统一 API Client，不直接感知具体微服务地址。

### 阶段 8：最后学习和实现 ai-service

先完成 Spring Cloud 主链路，再进入 Agent：

1. 根据已完成的 Assessment 输入定义 Agent Service 接口；
2. 先实现有限步骤的确定性状态流，不先接大模型；
3. 实现 Session、关键 Step 和 Checkpoint；
4. 接入一次真实模型调用并记录适度元数据；
5. 实现模糊、矛盾或视觉信息不一致时的重新提问；
6. 实现完成条件和最终结果；
7. 最后才评估是否需要 RAG 和 pgvector。

`ai_db` SQL 当前保持 `FUTURE / NOT APPLIED`。在重新学习 Agent 并审查设计前，不运行 AI migration，不让其他 Java 服务依赖这些表。

### 阶段 9：项目收尾

1. 跑全部 Maven 测试和前端构建；
2. 用微信开发者工具完成真机/模拟器验证；
3. 完成一条从登录到结果的端到端演示；
4. 检查 OpenAPI 与实现是否一致；
5. 整理 README、启动命令、架构图和演示截图；
6. 准备面试讲解：服务边界、状态机、事务、幂等、超时、熔断、MQ 和 Agent 恢复。

## 5. Service 接口设计检查表

每写一个 Service 方法，先回答：

- 这是业务动作，还是单纯把表 CRUD 暴露出来？
- 当前用户身份从哪里来？
- 哪些状态允许执行？执行后状态如何变化？
- 哪几次数据库写入必须同一事务？
- 重复调用会发生什么？
- 资源不存在与无权访问分别如何表现？
- 是否依赖别的服务？对方超时或失败怎么办？
- 返回 DTO 需要哪些数据？是否误暴露 Entity 或存储内部信息？
- 至少需要哪些正常和失败测试？

对于这个学习项目，Service 直接接收请求 DTO、返回响应 DTO 是可以接受的。只有当 HTTP 类型污染业务层时才做转换，例如不要让 `MultipartFile` 深入核心业务规则。

## 6. 给 AI 的单切片任务模板

```text
只处理【具体业务切片】。OpenAPI 和我的 Service 接口不允许擅自修改。

请完成机械基础设施：
1. 与现有 SQL 一致的 Entity 和 MyBatis-Plus Mapper；
2. Controller 对 Service 的薄委托；
3. DTO/Entity 转换；
4. 根据我给出的测试清单补测试骨架；
5. 编译并报告契约冲突。

不要设计业务规则，不要让 Controller 直接调用 Mapper，不要引入 DDD 分层，
不要实现跨服务调用、重试、Sentinel、MQ 或 Agent。发现必须由我决定的问题时列出，
不要自行改变架构。
```

## 7. 你现在立刻开始的任务

只做阶段 0 和阶段 1：

1. 启动 Docker 组；
2. 启动 Nacos、Gateway、history-service；
3. 确认注册和 Gateway 转发；
4. 打开创建、查询、恢复 Assessment 对应的 OpenAPI 与代码；
5. 写出这三个方法的 Service 接口；
6. 写出业务规则与测试清单；
7. 让 AI 补 Mapper 等机械代码；
8. 亲自完成第一个 ServiceImpl；
9. 通过测试和 Swagger 经 Gateway 验收。

不要先把全部 Service 接口定义完，也不要先做 Agent。第一条完整链路跑通后，再复制这套开发节奏。
