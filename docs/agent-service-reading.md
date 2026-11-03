# Agent Service 阅读与复现顺序

先读 `backend/service/ai/src/main/java/org/javaup/ai/service/impl/AgentServiceImpl.java` 的 public 方法，暂时把 private 方法当作名字清楚的操作。读通一条主流程后，再进入它调用的辅助方法。

目标是能解释“这个请求为什么读这些数据、修改哪些状态、在哪里提交”，不用背下全部字段。现在是 MVC；Mapper 管本库 SQL，Service 决定业务状态，模型、搜索和其他服务负责各自的外部能力。

## 先跑通的顺序

`create → get → advance → answer → advance → outcome → finalize`

其中 `answer` 只保存回答，`advance` 才执行下一轮分析。这是两个请求，便于用户退出、暂停、重试及控制外部调用成本。

**Agent 的 CREATED 表示“下一步待执行”，不是 History 的业务草稿。**创建任务后，以及保存一次回答后，都可能处于 CREATED。WAITING_FOR_USER 表示问题已经保存，用户关闭页面以后仍然可以回来回答。

## 固定值放在哪里

- `AgentEnums`：任务状态、步骤类型/状态、模型调用状态/用途/错误分类、正常结束原因与结果来源。Entity 保留字符串字段，Service 通过枚举的 `name()` 写入，数据库和 API 的既有值不变。
- `AIErrorCode`：AI 服务统一的错误码，Service 通过静态导入直接使用，例如 `MODEL_CALL_FAILED`。HTTP 状态与当前场景的说明仍写在异常产生的位置。
- Entity 的静态工厂负责成组初始化：`AgentSession.create`、`AgentCheckpoint.create`、`AgentStep.create`、`ModelCall.start`。工作流名称放在 `AgentSession`，固定安全提示保留在 `AgentServiceImpl`；查询、事务、序号分配和状态推进仍在 Service。
- 修改已有对象使用实例方法：`AgentSession.beginExecution / acceptCheckpoint / failExecution` 集中更新关联状态字段；`ModelCall.recordResponse / recordFailure` 与 `AgentStep.recordDecision / recordAnswer` 集中记录对应内容。Service 决定何时调用它们，并负责查询和持久化。
- 响应的纯字段转换放在 DTO 的 `fromEntity / fromDecision` 工厂中；`response` 仍负责读取当前分支和解释状态。简单校验、JSON 状态合并、分支查找保留在 Service，避免增加只包一行的抽象。
- 一次性说明文字、JSON 字段名留在使用位置，便于顺着数据流阅读。
- 模型业务提示词：`src/main/resources/prompts/symptom-agent.md`。这些提示词与接口错误说明分别维护。

## 逐个看这 13 个方法

| Service 方法 | 主要输入与返回 | 触碰的数据 | 事务边界与阅读重点 |
| --- | --- | --- | --- |
| `createAgentSession` | 当前用户、创建幂等键、Assessment 与可选模型/来源 Checkpoint；返回当前任务 | 新增 Session、初始 Checkpoint；读取来源 Session/Checkpoint | 先查幂等记录，再在事务外取 History/Image 输入；本地短事务建立任务、快照、指针。创建重试不能重新冻结另一份输入。 |
| `getAgentSession` | 当前用户、Session ID；返回版本、当前 Checkpoint、问题和当前分支近期步骤 | 读 Session、Checkpoint、Step | 只读 REPEATABLE_READ。先保证归属，再根据当前指针组装返回；不会启动模型。 |
| `getActiveSession` | 当前用户、Assessment ID；返回未结束任务 | 读 Session、Checkpoint、Step | 清理客户端缓存或换设备后，由 Assessment 找回 Session；没有任务才返回404，不会创建、恢复或调用模型。 |
| `answerAgentQuestion` | 幂等键、问题 ID、起点 Checkpoint、答案、expectedVersion | 新增 USER_INPUT Step 和 Checkpoint，更新 Session；使旧 ModelCall 失效 | 一个本地事务。先回放重复请求，再校验版本、当前问题和回答类型。保存后进入 CREATED，暂不调用模型。 |
| `advanceSession` | Session ID、expectedVersion；返回一次分析后的状态 | 预占 ModelCall；成功后新增 Step/Checkpoint，更新 Session 与调用元数据 | **三段式：短事务预占 → 事务外刷新图片并调用模型 → 短事务提交。**这是最值得仔细读的方法。 |
| `pauseSession` | Session ID、expectedVersion | 更新 Session，废弃本代未完成 ModelCall | 短事务。保存 PAUSED、递增执行代次；不是让供应商的模型进程停在某个 token。 |
| `resumeSession` | Session ID、expectedVersion | 读当前 Checkpoint；更新 Session 与旧 ModelCall | 短事务。从 PAUSED、FAILED 或已超期 RUNNING 接管。快照有待答问题就回 WAITING_FOR_USER，否则回 CREATED；不会回退预算。 |
| `reviseAnswer` | 原回答 Step ID、新答案、幂等键、expectedVersion | 读原回答之前的 Checkpoint；新增 Step/Checkpoint，更新 Session | 短事务。新回答连接原回答的父节点。旧回答及后续节点保留，不能把它们的事实混进新分支。 |
| `changeModel` | 服务端允许的 provider/model、expectedVersion | 更新 Session，废弃旧执行 | 先检查模型配置，再用短事务切换；已有历史调用保持实际使用的模型。切换不会重置调用或步骤预算。 |
| `cancelSession` | Session ID、expectedVersion | 更新 Session，废弃旧执行 | 短事务。取消后只读；完成的任务也不能靠取消再改写。重新分析需要新任务。 |
| `getAgentOutcome` | Session ID；返回保存的候选结果 | 读 Session 与当前 Checkpoint 的 outcome | 只读 REPEATABLE_READ。只有 COMPLETED 才有候选；不会重新生成结果，也不代表 History 已经定稿。 |
| `finalizeOutcome` | 当前用户、Session ID；返回 History 的结果版本与 ID | AI 只读已冻结结果；History 写自己的三张业务表 | 先在 AI 本地事务中读取，再在事务外调用 History。重复交付由 History 按 Session ID 幂等，不尝试跨两个数据库开一个事务。 |
| `listSteps` | 当前用户、Session ID；返回全部关键节点及父关系 | 读 Session、Step | 只读 REPEATABLE_READ。这里用于查看所有分支；模型上下文使用当前分支，不能直接把这个列表全量发送给模型。 |

## 再进入 private 方法

先看这几组，其他小方法用到时再点进去：

1. **`reserveExecution / completeExecution / recordFailure`**：发请求前如何占预算、外部调用后如何决定接受或丢弃响应，以及失败后为什么还能恢复。远程执行期间不占着数据库行锁。
2. **`appendAnswer / newStep / saveCheckpoint`**：一次回答怎样形成新节点、完整状态快照与新的当前指针。节点编号和 Checkpoint 编号在整个任务内递增，不是当前分支深度。
3. **`owned / checkpoint / state / expectVersion`**：用户归属、引用归属、状态格式与摘要、旧请求版本的检查各自解决什么问题。
4. **`response / activePath / outcome`**：返回体如何从当前指针构造；`activePath` 沿父关系取当前分支，不按全部步骤编号拼上下文。
5. **`replayCreate / replayAnswer / invalidateExecution`**：重试可以返回当前状态，但不能把当前指针切回旧分支；暂停、改答与切换模型怎样使旧工作失效。

`AgentJson` 是 JSONB 与稳定摘要的技术转换。它不是 Agent 决策器，里面没有“模型应该问什么”的规则。

## 跨服务数据流与所有权

AI 的入口是 `AgentAssessmentBridge`，实现位于 `service/impl/AgentAssessmentBridgeImpl.java`。它只编排外部读取和交付，不承包 Session 的事务。

| 内部接口 | 提供的能力 | 数据所有权与边界 |
| --- | --- | --- |
| `GET /internal/history/assessments/{assessmentId}/agent-input` | 校验所属用户及提交凭据，返回一致的业务输入与稳定输入摘要 | Assessment、基础症状、粗位置、视觉引用属于 History。摘要排除 answers、version、状态和时间，避免把追加答案误判成基础输入改变。 |
| `POST /internal/image/agent-pictures/resolve` | 最多 12 张图片一次批量读取，返回尺寸、来源、viewState、完整标注和临时读取地址 | 图片对象与 Annotation 属于 Image。查询图片、标注集、形状最多三次批量 SQL；标准图片可公共读取，用户图片仍校验归属。 |
| `POST /internal/history/assessments/{assessmentId}/agent-results` | 接受一个 Session 的定稿结果；写历史版本并更新当前结果 | History 独立校验用户、提交状态、输入摘要、分析 attempt 与重放内容，在一个本地事务里写 revision、当前投影和 Assessment 完成状态。 |

AI 保存的输入快照包含粗位置、用户文字、pictureId、尺寸、viewState、标注 ID/版本/形状。**签名 URL 不进入快照或 Checkpoint**，需要读图时再调用 Image 刷新。刷新 URL 不会把后来修改的 Annotation 覆盖进原快照。

History 的 `AgentResultServiceImpl` 可以接着按 `getAgentInput → finalizeResult` 阅读。定稿方法的关键顺序是：锁所属 Assessment → 优先处理同 Session 重放 → 拒绝迟到的旧 attempt → 比较稳定输入摘要 → 追加历史版本和更新当前投影。已有基础输入改变时返回 `ASSESSMENT_INPUT_CHANGED`，不能悄悄把旧输入的分析写成当前结论。

内部路径不经过前端 Gateway 路由；它们仍依赖可信服务网络和可信用户上下文，路径名本身不是鉴权机制。

## 复现时重点观察的几种情况

- 同一创建或回答幂等键重试：不新增另一份任务或答案，也不把当前分支切回去。
- 模型尚未返回时暂停或改答：旧响应回来可以记录耗时与 token，但不能推进新状态。
- 修改早期回答：`listSteps` 能看到旧分支，当前返回和模型上下文只使用新路径。
- 完成 AI 候选后 History 暂时不可用：候选仍保存在 AI，可重试定稿；History 不重复增加结果版本。
- 同一 Assessment 的新分析先定稿、旧分析后交付：旧 attempt 被拒绝，不能覆盖新结果。

一次 `advance` 在刷新图片之前就已经预占执行预算。因此图片刷新失败也会消耗一次预占，不把这个累计值当作供应商账单中的精确请求数。失败或丢弃的执行都不回退预算。

带来源 Checkpoint 创建新任务时，会保留来源的冻结输入。如果 History 的基础输入已经变更，旧输入结果在定稿时会被明确拒绝；需要针对当前输入分析时，应创建不带旧来源的任务。

## 当前运行条件

最终结果可以开启独立 B 模型评审。普通追问仍走原有流程；候选结果经校验后评审，最多一次 A 纠正和 B 复审。所有额外调用计入现有预算，失败不伪造成通过。配置、评审代码与合成案例入口见 [模型评审阅读说明](agent-review.md)。先读 `advanceSession` 的分支与预占，再读 `ReviewClient` 和纠正入口；不要把两模型通过当作医学正确性的证明。

- Nacos 的 `ai-service-dev.yaml` 已提供 AI 数据源、模型 profile 与搜索配置；默认选择智谱 `zhipu/glm-4.6v-flash`。本机密钥由模块外部文件 `ai-service.local.properties` 导入，不提交 Git，也不打入 JAR，配置方式见 AI 模块 README。
- `FakeAgentModel` 已移到 `src/test/java`，仅用于离线测试，不再是正式模型选项，也不作为失败兜底；真实运行缺少配置或调用失败会明确报错。
- 真正启用持久化前，需要人工审查并执行 AI V001/V002 与 History V003/V004；V004 保存结果来源快照。没有自动迁移或启动 Docker。AI SQL 暂时保留原 future 路径，不代表启动会替你建表。
- 真实模型需要配置允许的 provider/model、地址与密钥；搜索需要自己的配置。真实模型、搜索服务和远程图片读取尚未实测，这份参考实现不能据此宣称已经完成线上联调。
- `localhost` 的 MinIO 地址只对本机可达；当前实现由AI服务在允许的主机上下载图片，再作为图片字节提交给远程模型，云模型不直接访问localhost。密钥只放后端配置，不写入源码、数据库快照或前端。
- 可选 RAG 只读检索已实现，默认关闭且没有真实资料；见 [RAG 阅读说明](rag.md)。Agent 阶段观测、Trace 和指标见 [观测说明](agent-observability.md)。MCP、RabbitMQ、Outbox/Inbox 不属于当前 Agent 实现。
