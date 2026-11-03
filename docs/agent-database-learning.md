# Agent 数据模型与学习顺序

AI SQL 和 History V003 均 **NOT APPLIED**；没有启动 Docker或执行迁移。Agent Service 参考实现及对应 Mapper、Feign、模型/搜索适配已完成，逐方法阅读见 [Service 指南](agent-service-reading.md)。默认前端仍使用本地 Mock；真实 HTTP Adapter 已支持新的版本与 Checkpoint 契约，后端默认选显式 Fake 模型。

## 先确定业务行为

- 一次分析是一个 `agent_session`。用户退出页面不会丢失已提交状态；等待回答时保持 `WAITING_FOR_USER`。
- 用户明确暂停时变成 `PAUSED`。恢复从最近提交的 Checkpoint 继续，不是恢复模型供应商内部运行到一半的计算。
- 修改任意早期回答：从**那个回答之前**的 Checkpoint 建立新分支。旧回答及其后续节点保留，但不加入新分支的模型上下文。
- 每个 Session 只有一个当前 Checkpoint。不做多个分支并行分析；切换、改答都需要显式操作。
- `COMPLETED` / `CANCELLED` 不重新打开。再次分析建立新 Session，通过 `source_session_id/source_checkpoint_id` 追溯来源。`FAILED` 可在检查原因、预算后恢复，也可以取消。
- 同一个 Assessment 同时最多有一个未结束 Session；所有分支共享步骤和模型调用预算，回溯不重置预算。

## 1. agent_session：先看这张表

对应 `AgentSession.java`。它回答“是谁的哪一次分析，现在在哪里”。

| 字段 | 含义 |
| --- | --- |
| `id / assessment_id / user_id` | 本次任务、对应业务记录及所属用户。后两者是跨服务业务引用，没有跨库外键。 |
| `assessment_version` | 获取初始输入时 History 的版本，用于追溯。History 增加答案也会变更版本，因此不能用它与结束时版本相等来判断输入没变化。 |
| `attempt_no` | 同一个 Assessment 的第几次分析，重新分析时递增。 |
| `client_request_id / request_fingerprint` | 创建任务的幂等键与规范化请求 SHA-256；同用户同键返回原任务，同键不同内容拒绝。 |
| `status` | CREATED（当前输入已保存，待执行下一步）、RUNNING、WAITING_FOR_USER、PAUSED、COMPLETED、FAILED、CANCELLED。CREATED不等于History的业务草稿。 |
| `active_checkpoint_id` | **唯一当前状态指针**。Checkpoint 再指向当前 Step，不另设 `is_current` 或另一份 activeStep。 |
| `input_snapshot / input_snapshot_schema_version` | 冻结的基础症状、位置、图片和标注内容及其格式版本，避免恢复时悄悄读到后来修改过的标注。 |
| `source_session_id / source_checkpoint_id` | 新任务从旧任务哪个状态继续分析；不是本 Session 内的父节点。未来 Service 必须校验来源属于相同用户和 Assessment。 |
| `workflow_version / preferred_locale` | 使用哪套流程定义与交互语言。工作流版本负责解释状态结构，不等于数据库迁移版本。 |
| `model_provider / model_name` | 后续模型调用使用的配置，不存密钥。更换模型只影响后续调用；历史调用保留实际模型。 |
| `last_step_no` | 整个 Session 已分配的最大节点序号，包含旧分支，不是当前分支深度。 |
| `version` | Session 并发修改的乐观版本。 |
| `execution_generation` | 当前执行代次。暂停、改答、切换模型或接管超时执行时递增，使旧响应失效。 |
| `execution_deadline_at` | RUNNING 的接管期限；进程退出后，后续恢复请求可识别超时执行。到期不会自动改变数据库状态。 |
| `total_model_calls / max_model_calls / max_steps` | 全任务已预占调用数与上限、步骤上限。SQL 默认 20 次调用/40 个节点只是可配置保护值。 |
| `termination_reason_code / termination_detail` | 为什么完成、失败或取消，保存简短业务解释。恢复失败任务时清理旧结束信息。 |
| 各时间字段 | 首次执行、结束、创建、最近更新时间。`updated_at`、`version` 由应用主动更新。 |

区分三个数：`version` 防止旧请求覆盖新状态；`execution_generation` 防止旧模型响应覆盖新执行；`state_schema_version` 解释快照格式。它们都不是“第几轮”。

## 2. agent_step：再看树怎么保留

对应 `AgentStep.java`。只记录用户回答、信息提取、缩小范围、发现矛盾、重新确认、输出结果等关键节点。

| 字段 | 含义 |
| --- | --- |
| `session_id / step_no` | 属于哪个任务、在全任务中的分配顺序。 |
| `parent_step_id / parent_step_no` | 从哪个节点继续。父序号是有意冗余：复合外键保证父节点属于本任务，父编号小于子编号保证无环。 |
| `execution_generation` | 哪一代执行创建的节点。 |
| `step_type / status` | 节点做什么、是否已形成稳定输出。WAITING 表示已经提出问题，COMPLETED 表示本节点完成；这两类节点之后不覆盖。 |
| `client_operation_id / payload_fingerprint` | 用户回答的幂等键和内容摘要，摘要绑定起点 Checkpoint、具体问题和答案。 |
| `question_key / question / answer` | 稳定问题名、本节点提出的问题结构、用户回答。回答节点回复的是父状态中等待的问题；不能只用 questionKey 区分轮次。 |
| `input_references / extracted_facts / decision / contradiction_codes` | 使用了哪些输入、提取了什么事实、公开业务决策及矛盾代码。**不保存模型隐藏思维链。** |
| `user_visible_message` | 面向用户的问题、确认说明或摘要。 |

例如修改第一个回答：

```text
初始 Checkpoint（没有 Step）
  └─ Step 1：提问“麻木持续多久？”
       ├─ Step 2：旧回答“两周”
       │    └─ Step 3：基于“两周”的下一问（保留为旧分支）
       └─ Step 4：新回答“两天”
            └─ Step 5：基于“两天”的下一问（当前分支）
```

改答基于 Step 1 的 Checkpoint，不使用 Step 2 或 Step 3 的状态。后续上下文沿当前 Step 的 `parent_step_id` 链读取，不能按 `step_no` 把所有节点一起发送给模型。第一批节点的 parent 为空时，都连接同一个初始 Checkpoint。

重复提交旧回答只返回原操作已经接受的结果，**不能偷偷把 activeCheckpointId 切回旧分支**。幂等回放要先于当前状态/版本校验处理，同键不同内容仍拒绝。

## 3. agent_checkpoint：然后学恢复

对应 `AgentCheckpoint.java`。

- `session_id / step_id`：状态属于哪个任务、哪个已稳定节点。`step_id=null` 是唯一初始快照；每个稳定节点最多一个快照。
- `checkpoint_no`：快照在全 Session 的保存顺序，不是分支深度。
- `state_schema_version / state`：完整、可恢复的业务状态及格式版本，例如已确认事实、待回答问题、当前图片标注、结果候选。
- `state_checksum`：应用对规范化状态计算的 SHA-256；不能直接对随意序列化的 JSON 文本取摘要。

初始 Session、初始 Checkpoint 和当前指针在同一个 AI 本地事务中建立。后续也是“写节点、写快照、更新 Session 指针”一起提交。快照提交后不修改，数据库保证引用归属；不可覆盖旧节点/快照的业务约定由后续写入 Service 遵守。

`state` 不包含 Session 的 version、执行代次、累计调用预算；恢复快照不会倒退这些计数。图片保存 pictureId、像素尺寸、viewState 和 annotation 的版本及形状；短期签名 URL 在实际读取时重新获取。若原图被删除或不可读，恢复时需明确提示，不能假装图片仍然有效。

来源外键会保留溯源完整性：旧 Session 被新 Session 引用时，不能单独删除旧 Session。将来做用户数据清理时，需要把相关来源链一起处理。

## 4. model_call：最后理解调用与暂停的关系

对应 `ModelCall.java`，不是聊天记录表。

1. **请求前短事务**：锁定 Session，校验当前状态、版本、预算，增加 `total_model_calls`，分配 `call_no`，保存 STARTED 调用及 `base_checkpoint_id / execution_generation`，设置执行期限。
2. **事务外**调用模型。等待网络时不持有数据库行锁。
3. **返回后短事务**：只有 Session 仍 RUNNING、执行代次和起点 Checkpoint 都匹配时，才能提交新节点、快照并推进指针。
4. 用户暂停或改答会更新执行代次。迟到结果记为 DISCARDED，不得复活旧分支；已经发生的调用仍计入预算，返回的 token、耗时照样记录。

`provider/model_name/purpose` 说明调用了谁、做什么；`status` 说明是否成功、失败或因旧执行而未被采纳；其余字段记录平台请求号、trace、起止时间、耗时、token、分类错误和安全元数据。默认不存完整 Prompt/Response。每代最多一个 STARTED 调用；接管的新代可能遇到供应商仍在处理旧请求，因此暂停并不保证供应商停止计费。

## 5. History 结果：保留现有读取方式

新增 `assessment_result_revision / AssessmentResultRevision`，SQL 是 History V003，尚未应用。

- 原 `assessment_result` 保持当前结果，现有 `get` 结果方法继续按 assessmentId 读取。
- 新表保存每次定稿的完整结果，`revision_no` 在同一 Assessment 内递增；每个 Agent Session 最多定稿一次。
- `agent_session_id / agent_step_id / agent_checkpoint_id` 是 AI 来源引用，没有跨库外键。
- 以后定稿时，History 在**一个本地事务**中追加 revision、更新当前结果及相关 Assessment 状态。重试同一个 Session 要比较并返回已有结果，不能追加第二份。
- 没有回填旧结果。跨服务交付通过显式 finalize 方法完成；AI 快照中的候选结果不等于 History 已经定稿。新增 agent_attempt_no 防止旧分析迟到覆盖，assessment_input_fingerprint 校验稳定业务输入，避免用旧输入生成的结果覆盖已修改的记录。

## RAG 两张表先跳过

`knowledge_document / KnowledgeDocument` 表示知识来源；`knowledge_chunk / KnowledgeChunk` 表示切块及可选 embedding。都是 FUTURE / OPTIONAL。模型、维度和检索方案未选定，不加向量客户端/索引，Agent 运行不依赖它们。Entity 的 `PGobject embedding` 只是 PostgreSQL vector 列的映射预留。

## 数据库学完后的实现顺序

1. 创建 Session、冻结输入、建立初始 Checkpoint，并实现读取当前状态。
2. 实现暂停、恢复和超时接管，先用确定性 Fake 流程验证状态。
3. 实现回答入库与从历史回答分叉，验证旧分支不会混入当前上下文。
4. 接可替换的模型调用接口，加入有界执行、结构化结果校验和 ModelCall 记录。
5. 实现 History 结果定稿及结果历史，最后接前端真实交互；RAG 后置。

这些方法的参考实现与 DTO 已补齐：读取返回 Session version、activeCheckpointId；写入携带 expectedVersion，回答携带起点 Checkpoint、问题 ID 和操作幂等键；改答通过目标答案节点查回它之前的 Checkpoint。expectedStepNumber 仅保留为可选旧显示字段，不承担并发控制。创建与回答的同键重试校验内容，旧回答回放返回当前状态，不切回旧分支。

AI 模块现已使用完整 MP Boot4 Starter、PostgreSQL 驱动和 Spring AI 2.0.2，增加四个核心 Mapper、数据源样例与 UUID/PGobject TypeHandler。没有自动迁移，也不要求启动时提供真实模型密钥。JSONB 延续其他服务的 PGobject 风格。
