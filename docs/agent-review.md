# 最终结果模型评审

模型 A 负责当前 Agent 分析，模型 B 检查最终候选结果。这个功能用于发现输入不一致、忽略矛盾、无依据结论和不合适建议；B 的结论仍可能出错，不构成医学正确性的保证。

评审保留在现有 `ai-service` 中。后端配置 B 的 provider/model，前端不能临时指定评审模型、地址或密钥。默认关闭；显式开启后，缺少配置或调用失败会明确报错，不能被当作评审通过。

## 有界执行流程

```text
A 推进分析
  ├─ 继续追问或澄清：保持现有问答流程
  └─ 生成结果候选：Java 校验 → B 评审
       ├─ ACCEPT：接受候选
       ├─ REVISE：A 最多纠正一次 → B 最多复审一次
       │    ├─ ACCEPT：接受修正结果
       │    └─ REVISE / ABSTAIN：要求用户补充，不保存为已通过的最终结果
       └─ ABSTAIN：要求用户补充，不伪造通过
```

最多有 A、B、A 修正、B 复审四次模型请求。普通追问不会额外请求 B。Java 控制流程与上限；模型输出不能增加循环次数。纠正时保留原来的输入、当前分支和实际检索来源，不通过再次搜索为候选制造新依据。

当前仍采用有界的同步执行。每次模型请求默认超时 45 秒，最多四次就可能达到 180 秒，另有图片、检索与数据库开销。因此只给 AI 路由配置 240 秒网关响应窗口，前端 `advance` 请求为 250 秒，外层略长于网关；其他服务保留原有窗口。这是连接等待上限，不承诺所有输入一定在窗口内完成。

使用 Nacos 时，需要重新发布仓库里的 `gateway-dev.yaml` 并重启 Gateway；只改本地文件不会自动更新已经发布的配置。

页面退出、断网或 HTTP 等待超时不代表后端已停止，也不代表调用预算没有消耗。重新进入时先只读查询 Session（前端使用 `getAgentSession(assessmentId, false)`），查看当前状态和 Checkpoint；不要自动重发 `advance` 或复用旧版本再次推进。任务仍为 RUNNING 时按已有恢复/执行租约规则处理，出现 FAILED 或等待用户回答时再由明确操作继续。网络重试不能增加评审次数或绕过数据库预算。

B 获得本次输入、当前状态、候选结果和真实检索资料。需要判断图文是否一致时，它也获得相关图片字节；过期签名 URL 不作为持久化身份，也不直接发给模型。数据库仍为空时，评审必须承认没有本地知识依据，不能用“B 说通过”替代资料来源。

Java 先验证结构、枚举、问题/结果形状和可用来源。B 使用固定规则输出结构化判断：

```json
{
  "verdict": "REVISE",
  "issues": [
    {
      "code": "UNSUPPORTED_CLAIM",
      "reason": "现有输入和参考资料不支持这一确定性结论。"
    }
  ]
}
```

`ACCEPT` 表示在评审规则范围内未发现需要拦截的问题。`REVISE` 表示有具体问题可尝试纠正。`ABSTAIN` 表示评审无法确认，不能沿成功路径交付。弃权或复审仍不通过时，Java 保存明确的补充问题并进入 `WAITING_FOR_USER`；供应商故障或非法输出按失败处理，任务进入可恢复的 `FAILED`，不能伪造评审通过。问题最多五条，使用固定代码；不要求输出模型隐藏思维链。

问题代码：

| 代码 | 检查内容 |
| --- | --- |
| `INPUT_MISMATCH` | 手别、症状、时间或功能影响被遗漏、曲解 |
| `UNRESOLVED_CONTRADICTION` | 已存在的矛盾没有澄清 |
| `UNSUPPORTED_CLAIM` | 无依据结论或编造资料依据 |
| `UNSAFE_ADVICE` | 不合适的治疗、用药或强行负荷建议 |
| `MISSING_UNCERTAINTY` | 信息不足时仍给出确定性保证 |
| `VISUAL_MISMATCH` | 实际提供的图片与候选描述不一致 |
| `OTHER` | 有限规则未覆盖的具体问题 |

## 预算、状态与观测

每一次实际模型请求都单独预占预算、建立 ModelCall，并记录用途、平台、用量和结果。评审和纠正也消耗 Session 与用户每日额度；失败后不会把已预占的额度当作未使用。离线评测的供应商费用独立于业务用户额度。

调用外部模型时不持有 PostgreSQL 行锁或数据库事务。每次追加调用前重新检查 Session、执行代次与检查点；暂停、取消或切换分支后，迟到响应不能继续纠正，也不能完成旧任务。一次流程中不会同时保留多个 STARTED 调用来绕过幂等与并发约束。

ModelCall 的 `SUCCEEDED` 描述调用是否成功，不能单独代表最终结果已经被评审采用。是否采纳还要结合步骤、用途和评审元数据。B 的接口故障、超时、非法 JSON 和拒绝采纳分别保留明确状态；本轮没有“异常时默认通过”的分支。

已有 `purpose` 字段区分 `NEXT_SYMPTOM_STEP`、`QUALITY_REVIEW` 和 `RESULT_REVISION`。同一轮可以依次出现多个 SUCCEEDED：这表示 A、B 各自合法返回，不能据此把中间候选交付 History。B 合法返回 REVISE 或 ABSTAIN 时，调用本身可以成功，候选仍未通过评审。

Checkpoint 的动态状态保存 `qualityReview` 摘要，字段为 `verdict`、`reviewCallId`、`revisionCount`、`issueCodes`；它关联实际评审调用，不保存 B 的完整理由或隐藏推理。Session 和 Outcome 的公共响应本轮没有额外增加评审字段。需要查看执行情况时，使用所属任务的 `GET /api/ai/v1/agent-sessions/{sessionId}/observations`：调用记录提供 `purpose`、可空的 `reviewVerdict` 与 `reviewIssueCodes`，这些字段从安全元数据白名单读取，不返回 B 的 reason 原文。

观测沿用原有 Trace、阶段 Span、计时和 Token 指标。A、B 的请求均关联同一轮执行，但角色和调用用途不同。原生 SDK 错误事件也做脱敏，避免供应商回显进入 Trace。配置与查看方法见 [Agent 观测指南](agent-observability.md)。

## 开启方式

先在服务器的 `ai.models.profiles` 中增加 B 的白名单配置，地址和密钥使用环境变量或被 Git 忽略的本地配置。B 可以与 A 使用不同供应商，也可以使用同一供应商的不同模型；禁止把完全相同的 provider/model 冒充独立评审。

再设置：

```powershell
$env:AI_REVIEW_ENABLED = 'true'
$env:AI_REVIEW_PROVIDER = '你的评审平台标识'
$env:AI_REVIEW_MODEL = '白名单中对应的模型名称'
```

配置后重启 AI 服务。评审提示词独立保存为 Markdown 资源。不要把真实密钥写入提示词、前端、案例文件或版本库。

这个功能复用现有 `model_call.purpose`、`response_metadata` JSONB 和 Checkpoint State，不需要新增数据库表或迁移。原有 AI/History 运行迁移条件仍然适用。

## 固定案例离线评测

`src/test/resources/review-evaluation/cases.json` 有十个合成案例，覆盖准确摘要、合理不确定、错手、误读症状、忽略冲突、无依据确诊、编造依据、不合适建议、绝对保证和缺少基础信息。

这些案例把人工写出的候选当成 A 的输出，专门观察 B 能否发现预设问题。它们没有真实用户数据，也没有真实医学图像；不会验证视觉判断、真实 A 的总体质量或临床表现。期望判断是一套可人工讨论的规则，不是完整医学真值。

普通 `mvn test` 只校验案例与统计器，真实 API 评测默认跳过。需要自己选择供应商并明确允许付费请求时，再运行：

```powershell
$env:AI_REVIEW_EVAL_PROVIDER = '你的平台标识'
$env:AI_REVIEW_EVAL_MODEL = '实际模型名称'
$env:AI_REVIEW_EVAL_BASE_URL = '供应商兼容接口地址'
# API 密钥通过本机安全环境配置设置，不在命令示例中填写。
# 必须已设置 AI_REVIEW_EVAL_API_KEY。
./tools/review-evaluation/run.ps1 -AllowPaidCalls -MaxCalls 10
```

这个入口不启动 Spring 应用，不连接 Nacos、PostgreSQL 或 MinIO，不运行迁移。每个案例最多请求 B 一次，最多十次；不自动执行 A 的纠正循环。脚本和测试各有显式外部调用开关，普通构建不会产生 API 费用。

输出保存在被 Git 忽略的 `output/agent-review-evaluation/review-report.json`，包括逐例期望与实际 verdict/问题代码、耗时、供应商报告的 Token 和脱敏错误码。失败不计为通过；Token 缺失保持未知。报告同时给出判断一致数与预期问题命中数，不能把合成规则一致率称为医学准确率。命令成功只表示报告完成，不表示模型达到了上线质量门槛。完成后人工检查误判，再决定模型配置是否合适。

本轮没有执行真实供应商评测，不提供未实测的评审通过率、质量提升或费用数据。

## 阅读顺序

1. `AgentServiceImpl.advanceSession`：先看最终结果如何进入评审分支，以及调用预算与事务边界。
2. `ReviewClient`、`ReviewResult` 与 `SpringAIReviewClient`：看输入、结构化 verdict、后端白名单和错误处理。
3. A 的纠正入口：看为什么沿用原来源、最多纠正一次，避免引入新事实。
4. `ReviewEvaluationSupport` 与十个案例：看规则一致如何统计、失败和未知用量如何保留，再人工复核误判。

学习重点是评审器也会出错、结构校验与内容评审的分工、有界重试、调用预算、证据约束和可复现评测。两个模型不保证互相纠错，需要用真实但受控的案例继续验证。
