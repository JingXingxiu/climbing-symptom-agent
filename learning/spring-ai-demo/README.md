# 最小 Spring AI 学习模板

这是独立 Maven 工程，不加入现有 backend 聚合工程。Java 21、Spring Boot 4.0.6、Spring AI 2.0.2，与主项目一致。直接在 IDEA 打开本目录的 `pom.xml` 即可。

## 当前学习入口

| POST 路径 | Service 方法 | 当前状态 |
| --- | --- | --- |
| `/chat` | `chat` | 已完成：单轮文本调用，返回 `{"reply":"..."}` |
| `/chat/structured` | `structuredChat` | 已完成：格式提示 → 模型文本 → `AgentReply` → 少量字段检查 |
| `/chat/tools` | `chatWithTools` | 下一课动手入口，当前返回明确的 501，不调用模型 |

三个入口都使用 `Content-Type: application/json`，请求体为 `{"message":"..."}`。默认连接智谱 `glm-4.7-flash`。没有数据库、Nacos、Gateway、持久化或 Agent 状态机依赖。

结构化入口示例输入：`{"message":"我的中指有点麻。"}`，预期返回 `{"action":"ASK_QUESTION","message":"是左手还是右手？"}`；明确说明左右手后，按提示词规则返回 COMPLETE 和简短复述。COMPLETE 只表示这个演示的信息整理结束。

提示词在 `src/main/resources/prompts/structured-chat.md`。`AgentReply.Action` 枚举放在 record 内，没有独立异常类或验证框架。`BeanOutputConverter.getFormat()` 是格式提示，当前没有启用平台原生 JSON Schema 强制约束；`convert()` 负责反序列化，不执行完整 Schema 或业务规则校验。

已补充 Lombok `@Log4j2`。每次模型调用记录耗时，成功时记录 Token 使用量；不主动记录提示词、用户正文或密钥。总耗时不能区分平台排队、推理及网络各自占用的时间。

## 领取 API Key

核查日期：2026-10-31。平台政策和账号权限可能调整，领取时以控制台显示为准。

| 平台 | 模型和额度 | 官方入口 |
| --- | --- | --- |
| 智谱 BigModel，首选 | `glm-4.7-flash`，官方公布免费调用；免费不代表无限并发。纯文本，足够本次学习。 | [注册/登录](https://bigmodel.cn/)、[创建 API Key](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)、[免费公告](https://www.zhipuai.cn/zh/news/148)、[模型文档](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash) |
| 阿里云百炼，备选 | `qwen-flash`，符合条件的新实名认证主体首次开通，北京地域每模型 100 万 Token，90 天。主动开启“免费额度用完即停”，默认不会自动停止计费。 | [百炼控制台](https://bailian.console.aliyun.com/model/market)、[免费额度规则](https://help.aliyun.com/zh/model-studio/new-free-quota)、[价格表](https://help.aliyun.com/zh/model-studio/model-pricing) |

智谱注册后按控制台要求完成认证，创建普通模型 API Key 即可，不需要购买 Coding Plan。Key 保存在本机忽略文件或环境变量中。

## 启动

本机已配置工程根目录的 `application-local.yaml`，启动时自动加载。该文件已加入 `.gitignore`，也不进入应用 JAR。IDEA 运行配置的 Working directory 应为本 Demo 目录；当前机器无需重复填写环境变量。其他机器可以使用下面的环境变量方式。

推荐使用 IDEA：运行 `SpringAILearningApplication`，在该运行配置的 **Environment variables** 中添加：

```text
DEMO_AI_API_KEY=你自己的智谱APIKey
```

默认模型和地址已经配置好。没有有效 Key 时无法真实调用；未设置 Key 时启动可能因 SDK 凭据校验失败。

也可以在本目录的 PowerShell 中设置本进程环境变量并启动：

```powershell
$demoSecret = Read-Host '输入 API Key' -AsSecureString
$env:DEMO_AI_API_KEY = [System.Net.NetworkCredential]::new('', $demoSecret).Password
mvn spring-boot:run
```

输入的 Key 不会作为命令文本写进历史记录。Spring Boot 不会自动加载任意 `.env` 文件。

启动应用后，可以在另一个 PowerShell 窗口调用已有的单轮入口：

```powershell
$demoBody = @{ message = '你好，请用一句话介绍自己' } | ConvertTo-Json
Invoke-RestMethod `
    -Method Post `
    -Uri 'http://127.0.0.1:8099/chat' `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($demoBody))
```

只检查编译：`mvn compile`。本轮未编写或运行测试，也未发起真实模型请求。

## 下一课：工具调用

直接从 `DemoTools.currentTime` 和 `ChatService.chatWithTools` 开始：

1. 当前时间方法已经写好，添加 `@Tool` 并描述它的用途。
2. 在 `chatWithTools` 中使用注入的 `chatModel` 创建 `ChatClient`，把工具对象注册到本次请求。
3. 让模型回答“现在北京时间是多少？”，在工具方法处打断点，或观察“Java 工具 currentTime 被执行”的日志，确认实际执行。

Spring AI 2.0.2 的 `ChatClient` 支持自动工具执行流程。仅把工具声明放进 `ChatModel` 请求，并不等于执行了工具和后续模型调用；下一课会具体讲这两者的区别。不需要额外模型依赖或外部时间 API。

SDK 的 `ChatResponse` 表示模型响应；本模板的 `ChatResponseDto` 表示 HTTP 返回体。持久化、图片、RAG、重试和模型切换留到对应学习环节，本 Demo 当前不预建相关框架。

## 更换国产平台

使用 OpenAI 协议适配器不代表向 OpenAI 公司发请求，目标平台由 `base-url` 决定。三项环境变量分别控制 API Key、API 根地址和模型名称。

百炼北京地域可以使用以下配置，Key 要换成该地域的百炼 Key：

```text
DEMO_AI_API_KEY=你自己的百炼APIKey
DEMO_AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
DEMO_AI_MODEL=qwen-flash
```

百炼官方也提供带业务空间 ID 的新域名；这里使用官方仍支持的旧域名，减少 Demo 配置量。[百炼兼容协议文档](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope)

本工程使用 Spring AI **2.0.2**。智谱的根地址是 `https://open.bigmodel.cn/api/paas/v4`，SDK 追加 `/chat/completions`。不要复制旧教程的 `chat.completions-path` 配置，也不要把完整 `/chat/completions` 写入根地址。

Spring AI 2.0 的配置直接使用 `spring.ai.openai.chat.model`；旧文章可能写成 `chat.options.model`。以本工程配置和对应版本文档为准。

官方参考：[ChatModel](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)、[兼容协议适配和配置](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)。
