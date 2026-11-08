# 攀岩手部症状辅助分析系统

基于 Spring Cloud 和 Spring AI 的手部症状分析 Agent，配套 Vue 3 + uni-app 微信小程序。用户通过 3D 手部定位、图片标注和结构化描述表达不适，Agent 在有限步骤内补充信息、确认矛盾，并整理症状、参考依据和寻求专业帮助的提示。

系统围绕攀岩后的手部不适展开，支持痛、酸、胀、麻、肿、无力、僵硬、其他或不确定等多选症状，定位于症状表达与信息整理。

## 功能特性

- **3D 手部定位** — 支持左右手、旋转、缩放、区域点击与高亮，固定视角后生成可标注的截图。
- **统一图片标注** — 3D 截图、系统标准图和用户上传照片共用图片与标注模型，支持点选、画圈、自由绘制，使用归一化坐标适配不同分辨率。
- **有状态 Agent** — 通过 Session、Step、Checkpoint 保存分析过程，支持暂停恢复、超时接管和历史改答分支，提交时校验执行代次与起点。
- **模型与检索** — 基于 Spring AI 接入可配置模型，支持多模态输入、结构化输出、联网检索和可选 pgvector RAG，结果保留资料来源。
- **结果评审** — 可配置独立模型评审最终候选结果，最多纠正一次并复审，生成、评审和纠正统一纳入调用预算。
- **账号与历史** — 微信登录、用户资料、Assessment 草稿、历史记录、分析恢复和最终结果保存。
- **模拟套餐与额度** — 支持模拟购买、幂等确认和权益续期，通过 PostgreSQL 原子预占用户每日调用额度。
- **治理与观测** — Gateway 认证路由、OpenFeign 批量图片校验、Sentinel 限流与权益查询熔断，使用 Micrometer / OpenTelemetry 记录阶段耗时、Trace 和模型用量，支持接入 Jaeger。

## 使用流程

```text
创建 Assessment
    ↓
填写症状、开始时间、触发动作和使用影响
    ↓
3D 定位 / 标准图 / 上传照片 → 图片标注与文字补充
    ↓
Agent 追问 → 补充信息或确认矛盾 → 形成候选结果
    ↓
可选模型评审 → 保存最终结果 → 查询历史或重新分析
```

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.0.6、Spring MVC、MyBatis-Plus |
| 微服务 | Spring Cloud、Spring Cloud Alibaba、Nacos、Gateway、OpenFeign、Sentinel |
| AI | Spring AI 2.0.2、多模态模型、结构化输出、Tools、pgvector |
| 前端 | Vue 3、TypeScript、uni-app、three-platformize、微信小程序 Canvas |
| 数据存储 | PostgreSQL、JSONB、MinIO |
| 观测与运行环境 | Micrometer、OpenTelemetry、Jaeger、Docker Compose |

## 服务划分

前端通过 Gateway 访问业务 API，各服务使用 Controller → Service → Mapper 的 MVC 分层。

| 模块 | 职责 | 数据库 | 默认端口 |
| --- | --- | --- | --- |
| `gateway` | API 路由、JWT 校验、身份透传和入口限流 | — | 8080 |
| `base-service` | 微信登录、账号和用户资料 | `base_db` | 8081 |
| `image-service` | 图片上传、MinIO 对象元数据和 Annotation | `image_db` | 8082 |
| `history-service` | Assessment、症状位置、视觉引用、历史和最终结果 | `history_db` | 8083 |
| `ai-service` | Agent 编排、模型调用、检查点、检索、评审和用量 | `ai_db` | 8084 |
| `payment-service` | 模拟订单、套餐和用户权益 | `payment_db` | 8085 |

业务数据使用一个 PostgreSQL 实例，内部划分四个逻辑数据库。AI 使用独立的 PostgreSQL + pgvector 实例。各服务拥有自己的数据，通过 API 协作，跨服务引用保存业务 ID。

## 快速开始

准备 Node.js 22、pnpm 11 和微信开发者工具。运行后端还需要 Java 21、Maven，以及支持 Compose v2 的 Docker 环境。以下命令均从仓库根目录执行。

### 1. 运行小程序演示

```powershell
pnpm --dir frontend install
pnpm --dir frontend run type-check
pnpm --dir frontend run build:mp-weixin
```

将 `frontend/dist/build/mp-weixin` 导入微信开发者工具。前端默认使用本地 Mock，可直接体验症状采集、3D 定位、图片标注、有限追问、历史恢复和模拟购买流程。

开发时可以使用 `pnpm --dir frontend run dev:mp-weixin` 持续编译。

### 2. 启动本地基础设施

```powershell
if (!(Test-Path infra/.env)) {
    Copy-Item infra/.env.example infra/.env
}

docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --build --wait
docker compose --env-file infra/.env -f infra/docker-compose.yml ps
```

所有基础设施位于同一个 Compose 项目中，默认包含业务 PostgreSQL、AI PostgreSQL、MinIO、Nacos、Sentinel Dashboard、Swagger UI 和预留的 RabbitMQ。

| 入口 | 本地地址 |
| --- | --- |
| Swagger UI | <http://localhost:18081> |
| Nacos 控制台 | <http://localhost:18080> |
| MinIO 控制台 | <http://localhost:9001> |
| Sentinel Dashboard | <http://localhost:8858> |

数据库初始化、默认配置、日志及停止命令见 [基础设施说明](infra/README.md)。Jaeger 可通过同一 Compose 项目的 `observability` profile 启用，配置方式见 [观测文档](docs/agent-observability.md)。

### 3. 配置并运行后端

1. 按 [数据库说明](database/README.md) 和 [初始化命令](infra/README.md#apply-current-business-schemas) 手动执行各服务需要的 SQL。当前 History 代码需要 V003/V004，Agent 的 AI V001/V002 仍位于 `database/future/ai_db/`，标记为 `FUTURE / NOT APPLIED`，需审查后手动执行。
2. 启动 Nacos 后，将六个开发配置发布到 `CLIMBING_DEV` 分组。

   ```powershell
   & ./infra/nacos-config/publish-config.ps1
   ```

3. 配置数据库、MinIO、JWT、微信登录及模型平台所需参数。模型凭证使用服务端环境变量或本地配置，Base 与 Gateway 使用一致的 JWT 密钥。具体配置见 [Nacos 配置说明](infra/nacos-config/README.md)、[Agent 配置](docs/agent-service-reading.md) 和 [模拟支付配置](docs/payment-service.md)。
4. 编译后端，在 IDE 中以 `dev` profile 分别启动五个服务和 `GatewayApplication`。

   ```powershell
   mvn -f backend/pom.xml clean package
   ```

5. 在 `frontend/.env.local` 设置 HTTP 模式，重新构建小程序。

   ```dotenv
   VITE_API_MODE=http
   VITE_API_BASE_URL=http://localhost:8080
   ```

Gateway 默认监听 `http://localhost:8080`。Swagger UI 读取仓库内的 OpenAPI 文件，可用于查看字段、请求示例和调用接口。

## 当前状态

前端提供完整 Mock 演示，后端已包含五个服务的业务实现、持久化 Agent、模型适配、RAG 检索、结果评审和观测代码。构建与自动化验证记录见 [功能验证记录](docs/agent-features-verification.md)。

- Compose 负责准备运行环境，业务表由开发者按数据库说明手动初始化。
- RAG 与结果评审默认关闭，知识库初始为空。启用检索后，空库返回缺少参考依据的状态，模型分析可独立运行。
- 支付采用模拟确认流程。图片后台预处理仍处于后续实现阶段，RabbitMQ 当前作为预留基础设施。
- 真实模型平台、PostgreSQL / pgvector、Jaeger 和微信真机的完整端到端联调仍需在配置运行环境后完成。

## 目录结构

```text
backend/
├── gateway/              API 网关
└── service/
    ├── base/             账号与用户资料
    ├── image/            图片与标注
    ├── history/          症状记录与结果
    ├── ai/               Agent、模型、检索与评审
    └── payment/          模拟订单与权益
frontend/                 uni-app 微信小程序
database/                 各服务 SQL 与 AI 数据库脚本
infra/                    Docker Compose 与 Nacos 配置
docs/                     API、设计说明与验证记录
tools/                    手部资产处理与评测脚本
```

## 项目文档

- [OpenAPI 接口契约](docs/api/openapi.yaml)
- [Agent 数据模型](docs/agent-database-learning.md)
- [Agent 执行与恢复流程](docs/agent-service-reading.md)
- [RAG 检索与来源追溯](docs/rag.md)
- [最终结果模型评审与评测](docs/agent-review.md)
- [Agent 观测与 Jaeger 配置](docs/agent-observability.md)
- [模拟支付与 AI 额度](docs/payment-service.md)
- [Sentinel 限流与熔断](docs/sentinel.md)
- [批量 RPC 优化与性能记录](docs/project-highlights.md)
- [第三方资源与许可证记录](docs/open-source-and-licenses.md)

3D 手部模型采用 CC0 资源，标准手部图片由模型生成。来源、处理过程和依赖许可见 [资源记录](docs/open-source-and-licenses.md) 与 [资产处理说明](tools/README.md)。
