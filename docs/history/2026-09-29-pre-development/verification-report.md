# 交付验证报告

验证日期：2026-09-22

本报告记录本次交付实际执行过的自动化检查，以及仍需在微信开发者工具和真机上完成的验收。它不是医疗有效性验证报告。

## 已通过的检查

| 范围 | 检查 | 结果 |
| --- | --- | --- |
| 前端类型 | `pnpm run type-check` | 通过，无 TypeScript 错误 |
| 微信小程序构建 | `pnpm run build:mp-weixin` | 通过；生产产物位于 `frontend/dist/build/mp-weixin` |
| 构建产物 | 文件数量与总体积 | 64 个文件，1,481,869 bytes（约 1.413 MiB） |
| 关键产物 | `vendor.js` / `hand-lowpoly.glb` | 663,781 B / 480,996 B |
| 遗留资源 | 占位 3D 组件、旧标准图 SVG | 源码和最终产物均不存在 |
| API Contract | Redocly OpenAPI 3.1 minimal lint | 通过，引用和结构有效 |
| Compose | `docker compose config --quiet` | 通过 |
| 业务 PostgreSQL | 在干净 PostgreSQL 16 中执行三份当前 migration | `base_db`、`image_db`、`history_db` 均执行成功 |
| 数据约束 | 标准图片、Annotation 与症状词表约束 | 通过；合法症状多选可写入，非法 `ITCHING` 被约束拒绝 |
| AI PostgreSQL | pgvector 扩展 | 独立 `ai_db` 中可用 |
| FUTURE 隔离 | 检查 `agent_session` 是否被自动创建 | 未创建；未来 AI SQL 没有被应用 |
| MinIO / RabbitMQ | 使用 Compose 中固定镜像启动并检查 health | 两个服务均达到 healthy；验证容器随后已清理 |
| 交付环境清理 | `docker ps` | 无本项目验证容器残留 |
| 领域术语 | 搜索旧的疼痛中心类名、表名和路径 | 无业务残留；仅保留旧 Mock storage key 用于本地数据迁移 |

前端构建仅出现 Dart Sass legacy API 弃用提示和 uni-app 新版本提示，均不是构建错误。本项目为降低微信运行时兼容风险保留当前锁定版本，不在交付收尾阶段盲目升级。

## 资产核对

- 交互模型：`frontend/src/static/hand/hand-lowpoly.glb`，480,996 B。
- 标准图片：掌面、背面、侧面三张 PNG，均由同一 CC0 手部资产生成。
- 模型 SHA-256：`178B88B34C71791B25BCA47582FD3E90D8CCB1BD6538FCC8E30B43BBE5214927`。
- 资产来源、License、转换脚本和复现方法分别记录在 `docs/open-source-and-licenses.md` 与 `tools/README.md`。

## 契约一致性结论

- 聚合与位置统一使用 `Assessment` / `SymptomLocation`；图片内部仍可用中性的 `coarseLocation` 表达粗粒度位置。
- `SymptomType` 支持痛、酸、胀、麻、肿、无力、僵硬、其他/不确定八类合理多选，贯通前端、Mock、OpenAPI 与 `assessment_symptom`。
- Assessment 及用户资料的持久化版本从 1 开始，OpenAPI 与 SQL 一致。
- Annotation 保存请求允许 `version = 0` 表示首次创建；返回的持久化版本从 1 开始。
- 普通上传只接受用户图片或 3D 快照；系统标准图片通过只读列表获取。
- visual reference 只保存跨服务业务 ID，不建立跨数据库外键。
- `ai-service` 路径均标记为 `FUTURE_NOT_IMPLEMENTED`；当前前端默认走 Mock。
- `ai_db` migration 明确为 **FUTURE / NOT APPLIED**，Compose 没有执行入口。

## 仍需人工验收

当前机器已检测到微信开发者工具：`E:\Program\wx_dev\微信开发者工具.exe`，其 CLI 帮助可正常读取。自动打开项目时工具提示“服务端口已关闭”；本次没有擅自修改该安全设置，因此仍需手动导入 `frontend/dist/build/mp-weixin`，或由用户开启“设置 → 安全设置 → 服务端口”后再进行 CLI 自动化。以下内容不能由命令行构建替代，仍须在开发者工具和至少一台真机上验证：

1. WebGL / GLB 在目标基础库版本中真实加载；
2. 单指旋转、双指缩放、区域点击和页面滚动之间无手势冲突；
3. 左右手切换、粗区域高亮及稳定 `regionId` 正确；
4. 固定视角后截图不是黑图、方向正确，并能进入统一 Annotation 编辑器；
5. 点、圈、自由绘制在缩放及重新打开后仍按归一化坐标准确恢复；
6. 多图选择、临时文件持久化、反复进出页面和 WebGL 资源释放在 iOS / Android 上稳定。

完整人工清单见 `docs/development-guide.md`。真实 HTTP 联调也尚未执行，因为 Java 微服务、MinIO 上传接口、RabbitMQ Consumer 和真实 Agent 按约定不属于本次实现。

## 2026-09-26 基础设施增量检查

- 已将 Nacos 与 Sentinel Dashboard 加入原有 `infra/docker-compose.yml`，仍是单一 `symptom-assessment-dev` Compose 项目，没有创建分散的第二套 Compose 文件。
- `docker compose --env-file infra/.env.example -f infra/docker-compose.yml config --quiet` 通过，服务清单为两个 PostgreSQL、MinIO、RabbitMQ、Nacos、Sentinel Dashboard 共 6 项。
- Sentinel 镜像固定从官方 release JAR 本地构建；未采用第三方 Dashboard 镜像。
- 已实际执行 `docker compose up -d --build --wait`；6 个容器均进入 `healthy`，不是仅做 YAML 静态校验。
- Nacos readiness 返回 `{"code":0,"message":"success","data":"ok"}`；Sentinel Dashboard 首页返回 HTTP 200，样例自定义账号密码登录成功，未回退到官方默认密码。
- 业务 PostgreSQL 已创建 `base_db`、`image_db`、`history_db`；独立 AI PostgreSQL 的 `vector` 扩展版本为 `0.8.6`。没有执行三份业务 migration，也没有执行 FUTURE AI migration。
- 验证结束时整组容器保持运行，便于继续学习；可从 `infra/` 执行 `docker compose stop` 暂停，或执行 `docker compose down` 删除容器和网络但保留数据卷。
- 已增加同一 Compose 项目内的 Swagger UI。`http://localhost:18081` 与挂载后的 `/openapi.yaml` 均返回 HTTP 200，页面读取到“AI 手部症状辅助分析系统 API Contract”、本地 Gateway 地址及 Assessment 路径。
