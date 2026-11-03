# base-service

拥有 base_db 中的账号、微信身份和基础资料；保持 MVC，不访问其他服务的数据库。

## 按 Service 方法阅读

| 方法 | 数据流 |
| --- | --- |
| loginWithWechatCode | 检查签名配置 → 微信 code 换 OpenID → 本地事务创建或复用账号/资料/微信身份 → 提交后签发 JWT |
| getCurrentUser | 当前用户 ID → 检查账号存在、未删除且启用 → 查询资料 → UserProfileResponse.fromEntity |
| updateCurrentUser | 锁当前账号 → 检查资料版本 → 按 PATCH 提供的字段更新 → 版本加一并返回 |

主要入口：src/main/java/org/javaup/base/service/impl/UserAccountServiceImpl.java。
Entity 静态 create/apply 负责本地字段赋值；DTO 的 fromEntity 负责响应转换。

### 微信登录

小程序 wx.login 获取一次性 code，发送给现有微信登录接口。
WechatClient 仅向微信官方 jscode2session 地址请求，默认连接 3 秒、读取 5 秒；不自动重试一次性 code。
配置缺失返回 503；过期/已使用 code 返回 401；微信限频返回 429；上游异常返回 502/503。
不会返回或持久化 session_key，也不向前端暴露 OpenID，不记录凭证和供应商原始响应。

身份以 (WECHAT, appId, OpenID) 唯一识别。首次登录在同一个本地事务中创建三张表的记录；
重复登录复用 userId，只更新 last_login_at，不覆盖已修改的资料。
并发首次登录发生身份唯一冲突时，先让失败事务完整回滚，再在新事务读取获胜者，避免孤立账号。
微信网络请求位于事务外；本地账号提交后才签发 token。

登录请求 displayName 只初始化新用户；旧 avatarUrl 保留为兼容提示，目前不下载或保存它。
头像使用 image-service 上传后得到的 avatarPictureId；当前仅保存外部 ID，不做跨库关联或提前实现 Feign 图片校验。

### 资料 PATCH

version 必填。未提供的字段保持不变，显式 JSON null 清空该字段。
只提交 version 不写库、不递增版本；版本过期返回 409。
DTO 使用 JsonSetter 记录字段存在性，内部标志不会成为 JSON 字段。
更新采用账号行锁和 user_id + 原 version 条件；账号不存在/已删除返回 404，禁用返回 403。

## 运行配置

先按 infra/README.md 启动 Nacos 和业务 PostgreSQL，导入 base-service-dev.yaml，
并在 base_db 应用已有 database/base_db/V001__base_schema.sql。本轮没有新增迁移，也没有启动 Docker。

在启动 Base 的同一个 PowerShell 会话设置：

~~~powershell
$env:WECHAT_APP_ID = "<微信小程序 AppID>"
$env:WECHAT_APP_SECRET = "<微信小程序 AppSecret>"
$env:BASE_JWT_SECRET = "<至少 32 字节的随机密钥>"
mvn -pl service/base spring-boot:run
~~~

不要把真实密钥写入 Git。IDEA 启动时，在 Run Configuration 的环境变量中设置同名变量。
base.auth.issuer 默认 climbing-base-service，audience 默认 climbing-api，ttl 默认 2h。
签名配置缺失时先返回 503 AUTH_NOT_CONFIGURED，不消耗微信 code 或写账号；未配置微信返回 503 WECHAT_LOGIN_NOT_CONFIGURED。

使用 Nimbus JOSE JWT 生成 HS256 token，sub 为内部用户 UUID，含 iss/aud/iat/exp/jti；
令牌不含 OpenID、session_key 或用户资料。当前没有 refresh token、服务端会话或撤销列表。

## Gateway 身份链路

Base 签发真实 HS256 JWT；Gateway 已提供统一过滤器实现，校验签名、issuer、audience、
有效期和用户 UUID，清除外部 X-User-Id 后从 sub 注入可信身份。只有精确匹配
POST /api/base/v1/auth/wechat-login 的请求公开，其他匹配路由的请求需要 Bearer JWT。
Gateway 的 BASE_JWT_SECRET、issuer 和 audience 必须与 Base 一致，真实部署和 Nacos 联调仍待验证。

users/me 按既有契约接收可信内部 X-User-Id，并继续检查账号存在、未删除且启用。
JWT 当前没有撤销或禁用状态即时同步；History 不查询 Base。服务端口应限制为内部访问以防绕过 Gateway，
当前学习环境尚未部署网络隔离，不能据此宣称完整鉴权链路已验证。

## 检查

~~~powershell
mvn -pl service/base -am test
~~~

包含离线 Spring 启动、真实 Jackson3 PATCH 解析、微信 HTTP stub、JWT 签名/claims、
Service 幂等身份并发恢复与资料版本检查。测试不需要微信凭证、Nacos、Docker 或真实数据库；
事务测试验证调用顺序，不冒充真实 PostgreSQL 并发集成测试。
