# 可选 RAG 检索

当前实现读取已有 `knowledge_document` 与 `knowledge_chunk`，不导入任何真实资料、不执行 SQL migration。默认关闭。空库可以正常运行，模型必须知道本轮没有知识库依据。

## 一次检索的顺序

1. 未启用时返回 `DISABLED`，不访问数据库或 embedding 平台。
2. 检查 ACTIVE 文档中是否存在非空向量片段。真空库返回 `EMPTY`，即使尚未配置供应商也不调用 embedding。
3. 已有资料时，检查 embedding 平台、模型和维度配置。只检索与当前 `provider:model` 身份、维度完全匹配的向量。
4. 从结构化症状、手指和关节枚举生成公共查询。用户原文、身份、图片和签名 URL 不发送到 embedding 平台。
5. 使用 Spring AI `OpenAiEmbeddingModel` 连接配置的兼容平台，不手写供应商 HTTP 协议。网络调用不持有数据库事务；不启用自动重试。
6. 校验返回向量的维度、有限数值和非零模长；通过 MyBatis 绑定参数执行 pgvector 余弦距离查询。
7. 返回最多 top-K 条满足阈值的片段及文档 ID、片段 ID、标题、来源、内容版本和相似度；片段正文有硬上限。模型只能引用实际检索返回的来源身份。

`EMPTY` 表示没有可用或匹配的资料；`UNAVAILABLE` 表示表未应用、数据库/供应商失败或配置无效，不能混为一谈。异常原文、SQL、连接串和供应商响应不进入报告。两种情况都明确告知模型缺少本地证据，不伪造引用，也不把余弦分数当成医学判断的置信度。

## 配置

`rag-defaults.yaml` 给出默认值，AI 启动配置显式导入它。与聊天模型分别配置，因为聊天模型和 embedding 模型不能互换：

| 环境变量 | 用途 |
| --- | --- |
| `AI_RAG_ENABLED` | 是否尝试本地检索，默认 false |
| `AI_RAG_EMBEDDING_PROVIDER` | 平台标识，如 qwen；不固定要求 OpenAI |
| `AI_RAG_EMBEDDING_MODEL` | 实际 embedding 模型名称 |
| `AI_RAG_EMBEDDING_DIMENSIONS` | 预期向量维度 |
| `AI_RAG_EMBEDDING_BASE_URL` | 平台兼容接口地址；生产配置使用 HTTPS |
| `AI_RAG_EMBEDDING_API_KEY` | 独立供应商密钥，放环境变量/忽略的本地配置 |

真正空库只需显式开启开关并已建立表，即可观察 `EMPTY`；不需要为了空库配置付费密钥。表本身不存在时是 `UNAVAILABLE`，不会自动建表。

`top-k` 默认 3、最多 10；`min-score` 默认 0.65，可在 0–1 内配置；`max-content-chars` 默认 1600、硬上限 2000。`send-dimensions` 用于供应商是否接受请求里的 dimensions 参数；即使关闭发送，仍校验实际输出长度。

日后导入片段时，`knowledge_chunk.embedding_model` 必须写入完整身份 **`provider:model`**，而非只有模型名；`embedding_dimensions` 与真实向量长度一致。模型、维度或分词/预处理策略变化后，需要重新建立匹配索引，不能把不兼容向量混在一起比较。当前规模采用精确查询，没有为不存在的知识库提前建设 ANN 索引或导入平台。

## 怎么学

按 `KnowledgeRetrievalService.retrieve → queryFor → SpringAIKnowledgeEmbeddingClient.embed → KnowledgeRetrievalMapper.nearest` 阅读。

重点理解：为什么先查库再生成查询向量；embedding 与聊天模型的区别；余弦距离 `<=>` 与相似度 `1 - distance` 的关系；参数绑定为什么不会把向量当成 SQL；空资料、基础设施失败和检索未命中为什么要区分。

检索后的资料正文属于外部证据，不可覆盖系统指令。有效的文档/片段 ID 和可核对出处用于约束引用，不代表资料天然正确。当前没有真实语料，不能宣称检索质量、引用正确率或症状建议效果已经验证。

## 验证范围

单元测试覆盖关闭、空库零 embedding、缺配置、索引不兼容、异常脱敏、无效向量和结果上限；SQL 测试验证模型身份、维度、向量、阈值和数量均使用绑定参数；SDK HTTP stub 测试仅连接本机假供应商。真实 PostgreSQL/pgvector 查询、真实供应商和语料质量需之后单独联调。
