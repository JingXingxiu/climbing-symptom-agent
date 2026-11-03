# image-service

管理 `image_db` 的图片元数据、图片衍生文件和独立标注。原始文件保存在私有 MinIO 桶。

已实现 `uploadPicture`、`getPicture`、`listStandardPictures`、`deletePicture`，
以及 `getPictureAnnotationSet`、`replacePictureAnnotationSet`。图片异步预处理仍未实现。

## 上传支撑代码

- `MinioObjectStorage.put(bucketName, objectKey, input, sizeBytes, contentType)`：
  将已知大小的流写入 MinIO。调用者用 try-with-resources 关闭流；该组件不建桶、不读元数据、不改数据库。
- `MinioProperties.bucket()`：默认配置为 `symptom-pictures`，可用 `MINIO_BUCKET` 覆盖。
- `PictureAsset.create(ownerUserId, idempotencyKey, sourceType, bucketName, objectKey,
  originalFilename, contentType, sizeBytes, viewState)`：创建上传占位 Entity，状态为
  `INITIATED`，处理状态为 `PENDING`，处理批次为 1。`viewState` 是转换好的可空 JSONB；
  SHA-256 和宽高由 Service 校验文件后填写，不信任客户端给出的文件格式或尺寸。
- `PictureAssetMapper.insertIfAbsent(picture)`：按现有 `(owner_user_id, upload_idempotency_key)`
  部分唯一索引原子插入。返回 1 表示本请求获得上传资格，返回 0 后按用户和幂等键查询已有记录。
  其他约束或 SQL 错误仍应抛出，不能一律当作幂等命中。
- `UuidTypeHandler` / `PgObjectTypeHandler`：处理 PostgreSQL UUID 和 JSONB，
  限定在本服务注册。
- `pictureUploadTransaction`：注入的 `TransactionTemplate`，使用 `REQUIRES_NEW`。
  占位和最终状态更新分别调用一次；MinIO 上传放在两次调用之间。不要给整个上传方法添加事务。

```java
int inserted = pictureUploadTransaction.execute(status -> baseMapper.insertIfAbsent(picture));
// 上面的回调结束并提交后，才执行对象上传。
try (var input = file.getInputStream()) {
    minioObjectStorage.put(picture.getBucketName(), picture.getObjectKey(),
            input, file.getSize(), picture.getContentType());
}
// 再用一次 pictureUploadTransaction.execute(...) 条件更新完成状态。
```

上面只展示支撑 API 的使用方式；当前 Service 已实现文件校验、请求内容比对、幂等状态分支、
上传错误处理和最终返回。更新为 `AVAILABLE` / `FAILED` 时限定原状态为 `INITIATED`
且 `deleted_at IS NULL`，避免覆盖删除或其他并发状态。正常上传不单独持久化 `UPLOADED`。
不要在图片上传方法中调用 history-service 关联 Assessment；关联由现有独立接口负责。

## 运行配置

Nacos 配置文件为 `infra/nacos-config/image-service-dev.yaml`。修改后重新发布该配置并重启服务
（当前 `refreshEnabled=false`）。新增的配置项是：

```yaml
image:
  storage:
    minio:
      bucket: "${MINIO_BUCKET:symptom-pictures}"
```

在 MinIO 控制台 `http://localhost:9001` 创建这个桶并保持私有。使用当前配置的 MinIO 账号，
允许服务向该桶写对象；不需要将桶设为公开，也不必在每次上传时检查或创建桶。
本次没有自动操作运行中的 MinIO 或发布 Nacos 配置。

## multipart 兼容

Controller 读取 `metadata` 的原始 JSON 字节并反序列化、执行 DTO 校验，避免无字符集的文本 part 导致中文乱码。
同时支持 `uni.uploadFile` 的普通表单文本和 `application/json` part。
字段名仍是 `file`、`metadata`，前端无需调整。错误 JSON、JSON null 和字段校验失败返回 400 Problem。

文件大小限制、真实媒体类型检查、请求头幂等键与 `clientRequestId` 的一致性检查、
来源和视角的业务校验位于 `uploadPicture` 中。

## 独立标注：实现与审查顺序

1. `AnnotationSet`：保留源图宽高、所属用户/Assessment、格式版本和修订号。
   `create` 初始化标注集；`applyContent` 更新内容，不负责访问数据库。
2. `PictureAnnotation.create`：将每个图形的 geometry、style 转成两个 JSONB，保留 geometry 的 `kind`。
3. `AnnotationSetResponse.fromEntity`：将标注集和有序图形转换成响应，不执行查询。
4. `AnnotationSetMapper.insertIfAbsent`：按 `(picture_id, owner_user_id, assessment_id)`
   原子创建标注集；冲突返回 0，不捕获异常后继续查询。
5. `PictureAssetServiceImpl`：读取按用户和 Assessment 隔离；读取事务使用可重复读，
   防止标注集版本与图形列表来自不同快照。整组替换在一个事务中完成版本 CAS、删除旧图形和插入新图形。

首次保存传 `version=0`，响应版本为 1；更新传上次响应的版本，冲突返回 409。
`shapes=[]` 清空图形但保留标注集，`note=null` 清空备注。用户之间不共享标注，
即使标注的是同一张标准图片。`shapeId` 使用前端生成的全局 UUID，不复用其他标注集的 ID。
当前接口不推进 `FINAL` 状态，也不调用 history-service 验证 Assessment；
`assessmentId` 在这里是隔离标注上下文的外部业务 ID。

必须先手动执行 `database/image_db/V002__annotation_source_dimensions.sql`。
新库按 V001 → V002 执行，已有 V001 的库只补 V002；不自动迁移，不修改已执行的 V001。
从仓库 `infra/` 目录执行：

```powershell
docker compose exec -T business-postgres psql -U symptom_app -d image_db -v ON_ERROR_STOP=1 -f /workspace/database/image_db/V002__annotation_source_dimensions.sql
```

迁移会从原图回填旧标注集的宽高；旧图片缺少真实宽高时会停止并回滚，先修复元数据后重试。
不要填虚假尺寸，也不要为了重跑迁移删除业务表。

## 内部批量图片引用查询

`POST /internal/image/visual-references/resolve` 供 History 关联图片前使用，接收可信 `X-User-Id`、
`assessmentId` 和最多 12 个不重复的图片 ID。任一图片不可访问、已删除或非 `AVAILABLE`，整批失败。
成功响应按请求顺序给出 `pictureId/sourceType/annotationSetId`；缺标注合法且返回 null。

非空成功请求固定两次批量 SELECT，只取必要字段：可访问图片一次、用户/Assessment 标注集一次。
空数组为零查询，图片校验失败提前结束为一次查询。不读取图形、衍生图，不生成 MinIO 签名 URL。
读取使用可重复读快照，原有单图公开接口保持不变。

现有 Gateway 只路由 `/api/...`，不会转发这个 `/internal/...` 路径；路径命名不等于鉴权。
当前未实现服务身份认证，部署时仍须限制服务端口直接暴露和非可信调用。
查询次数由 Mapper 调用次数测试验证，实际数据库耗时尚未做生产/真实 PostgreSQL 性能测试。
