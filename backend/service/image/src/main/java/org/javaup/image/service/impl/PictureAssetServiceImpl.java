package org.javaup.image.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

import lombok.AllArgsConstructor;
import org.javaup.image.api.dto.*;
import org.javaup.image.api.problem.ApiException;
import org.javaup.image.config.MinioProperties;
import org.javaup.image.entity.AnnotationSet;
import org.javaup.image.entity.PictureAnnotation;
import org.javaup.image.entity.PictureAsset;
import org.javaup.image.entity.PictureVariant;
import org.javaup.image.mapper.AnnotationSetMapper;
import org.javaup.image.mapper.PictureAnnotationMapper;
import org.javaup.image.mapper.PictureAssetMapper;
import org.javaup.image.service.PictureAssetService;
import org.javaup.image.service.PictureVariantService;
import org.javaup.image.storage.MinioObjectStorage;
import org.javaup.image.storage.MinioObjectUrlSigner;
import org.postgresql.util.PGobject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

@Service
@AllArgsConstructor
public class PictureAssetServiceImpl extends ServiceImpl<PictureAssetMapper, PictureAsset>
        implements PictureAssetService {

    private static final String THUMBNAIL_VARIANT_TYPE = "THUMBNAIL";

    private final ObjectMapper objectMapper;
    private final PictureVariantService pictureVariantService;
    private final MinioObjectUrlSigner minioObjectUrlSigner;
    private final MinioObjectStorage minioObjectStorage;
    private final MinioProperties minioProperties;
    private final TransactionTemplate pictureUploadTransaction;
    private final AnnotationSetMapper annotationSetMapper;
    private final PictureAnnotationMapper pictureAnnotationMapper;

    @Override
    public PictureResponse uploadPicture(
            UUID currentUserId,
            UUID idempotencyKey,
            MultipartFile file,
            PictureUploadMetadata metadata
    ) {
        if (currentUserId == null || idempotencyKey == null
                || metadata == null || metadata.sourceType() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_UPLOAD_REQUEST",
                    "User, idempotency key and source type are required"
            );
        }

        if (!idempotencyKey.equals(metadata.clientRequestId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_MISMATCH",
                    "Header idempotency key must match clientRequestId"
            );
        }

        if (metadata.sourceType() == VisualSourceType.STANDARD_REFERENCE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_UPLOAD_SOURCE",
                    "Standard pictures cannot be uploaded through this endpoint"
            );
        }

        if (metadata.sourceType() == VisualSourceType.MODEL_SNAPSHOT
                && metadata.viewState() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VIEW_STATE_REQUIRED",
                    "A model snapshot requires viewState"
            );
        }

        if (metadata.sourceType() == VisualSourceType.USER_UPLOAD
                && metadata.viewState() != null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "UNEXPECTED_VIEW_STATE",
                    "A user photo must not contain model viewState"
            );
        }

        UploadImage image = readUploadImage(file);

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && originalFilename.length() > 255) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "FILENAME_TOO_LONG",
                    "Original filename must not exceed 255 characters"
            );
        }

        PGobject viewState = null;
        if (metadata.viewState() != null) {
            viewState = new PGobject();
            viewState.setType("jsonb");
            try {
                viewState.setValue(
                        objectMapper.writeValueAsString(metadata.viewState())
                );
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Failed to prepare snapshot view state",
                        exception
                );
            }
        }

        // 对象路径由服务端生成，不使用客户端文件名。
        String extension = image.contentType().equals("image/png")
                ? "png" : "jpg";

        String objectKey = "users/" + currentUserId + "/"
                + UUID.randomUUID() + "." + extension;

        PictureAsset picture = PictureAsset.create(
                currentUserId,
                idempotencyKey,
                metadata.sourceType(),
                minioProperties.bucket(),
                objectKey,
                originalFilename,
                image.contentType(),
                image.bytes().length,
                viewState
        );

        picture.setSha256(image.sha256());
        picture.setWidthPx(image.width());
        picture.setHeightPx(image.height());

        // 第一次短事务：提交占位记录，确定谁负责上传。
        int inserted = pictureUploadTransaction.execute(
                status -> baseMapper.insertIfAbsent(picture)
        );

        if (inserted == 0) {
            // 必须包含已删除记录，因为它仍占用这个幂等键。
            PictureAsset existing = lambdaQuery()
                    .eq(PictureAsset::getOwnerUserId, currentUserId)
                    .eq(
                            PictureAsset::getUploadIdempotencyKey,
                            idempotencyKey.toString()
                    )
                    .one();

            if (existing == null) {
                throw new IllegalStateException(
                        "Idempotent picture exists but cannot be loaded"
                );
            }

            if (existing.getDeletedAt() != null
                    || PictureUploadStatus.DELETED.name()
                    .equals(existing.getStorageStatus())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "PICTURE_ALREADY_DELETED",
                        "The picture associated with this key was deleted"
                );
            }

            var existingView = existing.getViewState() == null
                    ? null
                    : objectMapper.readTree(existing.getViewState().getValue());

            var requestedView = viewState == null
                    ? null
                    : objectMapper.readTree(viewState.getValue());

            boolean sameRequest =
                    Objects.equals(existing.getSha256(), image.sha256())
                            && Objects.equals(
                            existing.getSizeBytes(),
                            (long) image.bytes().length
                    )
                            && existing.getSourceType()
                            .equals(metadata.sourceType().name())
                            && Objects.equals(existingView, requestedView);

            if (!sameRequest) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "The same key cannot be used for different upload content"
                );
            }

            if (PictureUploadStatus.AVAILABLE.name()
                    .equals(existing.getStorageStatus())) {
                return getPicture(currentUserId, existing.getId());
            }

            // INITIATED 不重复上传；FAILED 也不自动夺回上传资格。
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "UPLOAD_NOT_AVAILABLE",
                    "Existing upload state: " + existing.getStorageStatus()
            );
        }

        // 数据库占位已经提交。这里没有数据库事务。
        try (var input = new ByteArrayInputStream(image.bytes())) {
            minioObjectStorage.put(
                    picture.getBucketName(),
                    picture.getObjectKey(),
                    input,
                    image.bytes().length,
                    image.contentType()
            );
        } catch (IOException | RuntimeException exception) {
            ApiException failure = new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PICTURE_UPLOAD_FAILED",
                    "Failed to upload the picture to object storage"
            );
            failure.initCause(exception);

            try {
                pictureUploadTransaction.executeWithoutResult(status -> {
                    lambdaUpdate()
                            .eq(PictureAsset::getId, picture.getId())
                            .eq(PictureAsset::getOwnerUserId, currentUserId)
                            .eq(
                                    PictureAsset::getStorageStatus,
                                    PictureUploadStatus.INITIATED.name()
                            )
                            .isNull(PictureAsset::getDeletedAt)
                            .set(
                                    PictureAsset::getStorageStatus,
                                    PictureUploadStatus.FAILED.name()
                            )
                            .set(PictureAsset::getUpdatedAt, OffsetDateTime.now())
                            .update();
                });
            } catch (RuntimeException databaseFailure) {
                failure.addSuppressed(databaseFailure);
            }

            throw failure;
        }

        // 第二次短事务：只有仍然有效的占位记录才能变成 AVAILABLE。
        OffsetDateTime now = OffsetDateTime.now();

        boolean completed = Boolean.TRUE.equals(
                pictureUploadTransaction.execute(status -> lambdaUpdate()
                        .eq(PictureAsset::getId, picture.getId())
                        .eq(PictureAsset::getOwnerUserId, currentUserId)
                        .eq(
                                PictureAsset::getStorageStatus,
                                PictureUploadStatus.INITIATED.name()
                        )
                        .isNull(PictureAsset::getDeletedAt)
                        .set(
                                PictureAsset::getStorageStatus,
                                PictureUploadStatus.AVAILABLE.name()
                        )
                        .set(PictureAsset::getUpdatedAt, now)
                        .update())
        );

        if (!completed) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PICTURE_STATE_CONFLICT",
                    "Picture state changed during upload"
            );
        }

        picture.setStorageStatus(PictureUploadStatus.AVAILABLE.name());
        picture.setUpdatedAt(now);

        return PictureResponse.fromEntity(
                picture,
                minioObjectUrlSigner.signGet(
                        picture.getBucketName(),
                        picture.getObjectKey()
                ),
                null, // 上传时尚未生成缩略图。
                metadata.viewState()
        );
    }

    private UploadImage readUploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "EMPTY_PICTURE",
                    "Picture must not be empty"
            );
        }

        if (file.getSize() > 10L * 1024 * 1024) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PICTURE_TOO_LARGE",
                    "Picture must not exceed 10 MB"
            );
        }

        try {
            // 文件大小有上限，因此这里使用内存保存本次上传内容。
            byte[] bytes = file.getBytes();

            try (var input = new MemoryCacheImageInputStream(
                    new ByteArrayInputStream(bytes))) {

                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new ApiException(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "UNSUPPORTED_PICTURE_TYPE",
                            "Only JPEG and PNG pictures are supported"
                    );
                }

                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);

                    String contentType = switch (
                            reader.getFormatName().toLowerCase(Locale.ROOT)
                            ) {
                        case "jpeg", "jpg" -> "image/jpeg";
                        case "png" -> "image/png";
                        default -> throw new ApiException(
                                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                "UNSUPPORTED_PICTURE_TYPE",
                                "Only JPEG and PNG pictures are supported"
                        );
                    };

                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);

                    if (width <= 0 || height <= 0
                            || (long) width * height > 40_000_000L) {
                        throw new ApiException(
                                HttpStatus.BAD_REQUEST,
                                "INVALID_PICTURE_DIMENSIONS",
                                "Picture dimensions are invalid or exceed 40 million pixels"
                        );
                    }

                    String sha256 = HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes)
                    );

                    return new UploadImage(
                            bytes, contentType, width, height, sha256
                    );
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PICTURE",
                    "Picture content could not be read"
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record UploadImage(
            byte[] bytes,
            String contentType,
            int width,
            int height,
            String sha256
    ) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<PictureResponse> listStandardPictures(
            HandSide handSide,
            StandardPictureView view
    ) {
        var query = lambdaQuery()
                .eq(
                        PictureAsset::getSourceType,
                        VisualSourceType.STANDARD_REFERENCE.name()
                )
                .eq(
                        PictureAsset::getStorageStatus,
                        PictureUploadStatus.AVAILABLE.name()
                )
                .isNull(PictureAsset::getDeletedAt);

        if (handSide != null) {
            query.eq(PictureAsset::getStandardHandSide, handSide.name());
        }

        if (view != null) {
            query.eq(PictureAsset::getStandardView, view.name());
        }

        return query
                .orderByAsc(PictureAsset::getStandardAssetKey)
                .list()
                .stream()
                .map(picture -> PictureResponse.fromEntity(
                        picture,
                        minioObjectUrlSigner.signGet(
                                picture.getBucketName(),
                                picture.getObjectKey()
                        ),
                        null, // 标准图库直接使用原图，无需单独缩略图
                        null  // 标准图片没有 3D 视角信息
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PictureResponse getPicture(
            UUID currentUserId,
            UUID pictureId
    ) {
        PictureAsset picture = requireAccessiblePicture(
                currentUserId,
                pictureId
        );

        PictureVariant thumbnail = findCurrentThumbnail(picture);
        var originalUrl = minioObjectUrlSigner.signGet(
                picture.getBucketName(),
                picture.getObjectKey()
        );
        var thumbnailUrl = thumbnail == null
                ? null
                : minioObjectUrlSigner.signGet(
                        thumbnail.getBucketName(),
                        thumbnail.getObjectKey()
                );

        return PictureResponse.fromEntity(
                picture,
                originalUrl,
                thumbnailUrl,
                readViewState(picture)
        );
    }

    private PictureVariant findCurrentThumbnail(PictureAsset picture) {
        return pictureVariantService.lambdaQuery()
                .eq(PictureVariant::getPictureId, picture.getId())
                .eq(PictureVariant::getVariantType, THUMBNAIL_VARIANT_TYPE)
                .eq(
                        PictureVariant::getProcessingGeneration,
                        picture.getProcessingGeneration()
                )
                .one();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ResolvedVisualReference> resolveVisualReferences(
            UUID currentUserId,
            ResolveVisualReferencesRequest request
    ) {
        if (currentUserId == null || request == null
                || request.assessmentId() == null || request.pictureIds() == null
                || request.pictureIds().size() > 12) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_VISUAL_REFERENCES",
                    "User, assessmentId and pictureIds are required; maximum 12 pictures"
            );
        }

        Set<UUID> uniqueIds = new HashSet<>();
        for (UUID pictureId : request.pictureIds()) {
            if (pictureId == null || !uniqueIds.add(pictureId)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PICTURE_IDS",
                        "pictureIds must not contain null or duplicate IDs"
                );
            }
        }
        if (request.pictureIds().isEmpty()) {
            return List.of();
        }

        // One scoped query, regardless of the number of pictures. Do not sign
        // URLs, resolve thumbnails, or load annotation geometry for this API.
        List<PictureAsset> pictures = baseMapper.selectList(
                Wrappers.<PictureAsset>lambdaQuery()
                        .select(PictureAsset::getId, PictureAsset::getSourceType,
                                PictureAsset::getStorageStatus)
                        .in(PictureAsset::getId, request.pictureIds())
                        .isNull(PictureAsset::getDeletedAt)
                        .and(condition -> condition
                                .eq(PictureAsset::getSourceType,
                                        VisualSourceType.STANDARD_REFERENCE.name())
                                .or()
                                .eq(PictureAsset::getOwnerUserId, currentUserId))
        );
        Map<UUID, PictureAsset> picturesById = new HashMap<>();
        for (PictureAsset picture : pictures) {
            picturesById.put(picture.getId(), picture);
        }

        // Never silently drop missing, deleted or inaccessible references.
        if (picturesById.size() != request.pictureIds().size()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PICTURE_NOT_FOUND",
                    "One or more pictures do not exist or are not accessible"
            );
        }
        for (UUID pictureId : request.pictureIds()) {
            PictureAsset picture = picturesById.get(pictureId);
            if (picture == null) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PICTURE_NOT_FOUND",
                        "One or more pictures do not exist or are not accessible"
                );
            }
            if (!PictureUploadStatus.AVAILABLE.name().equals(picture.getStorageStatus())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "PICTURE_NOT_AVAILABLE",
                        "Picture is not available: " + pictureId
                );
            }
        }

        List<AnnotationSet> annotationSets = annotationSetMapper.selectList(
                Wrappers.<AnnotationSet>lambdaQuery()
                        .select(AnnotationSet::getId, AnnotationSet::getPictureId)
                        .in(AnnotationSet::getPictureId, request.pictureIds())
                        .eq(AnnotationSet::getOwnerUserId, currentUserId)
                        .eq(AnnotationSet::getAssessmentId, request.assessmentId())
        );
        Map<UUID, UUID> annotationSetIds = new HashMap<>();
        for (AnnotationSet annotationSet : annotationSets) {
            annotationSetIds.put(annotationSet.getPictureId(), annotationSet.getId());
        }

        // SQL IN does not promise an order; return the caller's original order.
        return request.pictureIds().stream()
                .map(pictureId -> new ResolvedVisualReference(
                        pictureId,
                        VisualSourceType.valueOf(picturesById.get(pictureId).getSourceType()),
                        annotationSetIds.get(pictureId)
                ))
                .toList();
    }

    private PictureAsset requireAccessiblePicture(
            UUID currentUserId,
            UUID pictureId
    ) {
        PictureAsset picture = lambdaQuery()
                .eq(PictureAsset::getId, pictureId)
                .isNull(PictureAsset::getDeletedAt)
                .and(condition -> condition
                        .eq(
                                PictureAsset::getSourceType,
                                VisualSourceType.STANDARD_REFERENCE.name()
                        )
                        .or()
                        .eq(
                                PictureAsset::getOwnerUserId,
                                currentUserId
                        ))
                .one();

        if (picture == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PICTURE_NOT_FOUND",
                    "Picture does not exist"
            );
        }

        return picture;
    }

    private ViewState3D readViewState(PictureAsset picture) {
        if (picture.getViewState() == null
                || picture.getViewState().getValue() == null
                || picture.getViewState().getValue().isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(
                    picture.getViewState().getValue(),
                    ViewState3D.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Invalid view_state JSON for picture " + picture.getId(),
                    exception
            );
        }
    }

    @Override
    @Transactional
    public void deletePicture(UUID currentUserId, UUID pictureId) {
        OffsetDateTime now = OffsetDateTime.now();

        boolean deleted = lambdaUpdate()
                .eq(PictureAsset::getId, pictureId)
                .eq(PictureAsset::getOwnerUserId, currentUserId)
                .isNull(PictureAsset::getDeletedAt)
                .set(PictureAsset::getDeletedAt, now)
                .set(
                        PictureAsset::getStorageStatus,
                        PictureUploadStatus.DELETED.name()
                )
                .set(PictureAsset::getUpdatedAt, now)
                .update();

        if (deleted) {
            return;
        }

        boolean alreadyDeleted = lambdaQuery()
                .eq(PictureAsset::getId, pictureId)
                .eq(PictureAsset::getOwnerUserId, currentUserId)
                .isNotNull(PictureAsset::getDeletedAt)
                .exists();

        if (!alreadyDeleted) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PICTURE_NOT_FOUND",
                    "Picture does not exist"
            );
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnnotationSetResponse getPictureAnnotationSet(
            UUID currentUserId,
            UUID pictureId,
            UUID assessmentId
    ) {
        requireAccessiblePicture(currentUserId, pictureId);

        AnnotationSet annotationSet = requireAnnotationSet(
                currentUserId, pictureId, assessmentId
        );
        List<PictureAnnotation> annotations = pictureAnnotationMapper.selectList(
                Wrappers.<PictureAnnotation>lambdaQuery()
                        .eq(PictureAnnotation::getAnnotationSetId, annotationSet.getId())
                        .orderByAsc(PictureAnnotation::getSortOrder)
        );

        return AnnotationSetResponse.fromEntity(annotationSet, annotations, objectMapper);
    }

    @Override
    @Transactional
    public AnnotationSetResponse replacePictureAnnotationSet(
            UUID currentUserId,
            UUID pictureId,
            ReplaceAnnotationSetRequest request
    ) {
        PictureAsset picture = requireAccessiblePicture(currentUserId, pictureId);
        if (!PictureUploadStatus.AVAILABLE.name().equals(picture.getStorageStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PICTURE_NOT_AVAILABLE",
                    "Picture is not available for annotation"
            );
        }

        // Controller @Valid checks individual fields; these are cross-field rules.
        Set<UUID> shapeIds = new HashSet<>();
        for (AnnotationShape shape : request.shapes()) {
            if (shape == null || shape.shapeId() == null
                    || shape.type() == null || shape.geometry() == null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ANNOTATION",
                        "Annotation shape, id, type and geometry are required"
                );
            }
            if (!shapeIds.add(shape.shapeId())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "DUPLICATE_ANNOTATION_ID",
                        "shapeId must be unique within the annotation set"
                );
            }

            boolean geometryMatches = switch (shape.type()) {
                case POINT -> shape.geometry() instanceof AnnotationGeometry.Point;
                case CIRCLE -> shape.geometry() instanceof AnnotationGeometry.Circle;
                case POLYLINE -> shape.geometry() instanceof AnnotationGeometry.Polyline;
                case POLYGON -> shape.geometry() instanceof AnnotationGeometry.Polygon;
            };
            if (!geometryMatches) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "ANNOTATION_GEOMETRY_MISMATCH",
                        "Annotation type does not match its geometry"
                );
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        AnnotationSet annotationSet;
        if (request.version() == 0) {
            annotationSet = AnnotationSet.create(currentUserId, pictureId, request, now);
            if (annotationSetMapper.insertIfAbsent(annotationSet) == 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ANNOTATION_SET_EXISTS",
                        "Annotation set already exists; reload its version"
                );
            }
        } else {
            annotationSet = requireAnnotationSet(currentUserId, pictureId, request.assessmentId());
            if (request.version() == Integer.MAX_VALUE) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ANNOTATION_VERSION_EXHAUSTED",
                        "Annotation version cannot be incremented"
                );
            }
            int nextRevision = request.version() + 1;
            int updated = annotationSetMapper.update(
                    null,
                    Wrappers.<AnnotationSet>lambdaUpdate()
                            .eq(AnnotationSet::getId, annotationSet.getId())
                            .eq(AnnotationSet::getOwnerUserId, currentUserId)
                            .eq(AnnotationSet::getRevision, request.version())
                            .eq(AnnotationSet::getStatus, "DRAFT")
                            .set(AnnotationSet::getSourceWidth, request.sourceWidth())
                            .set(AnnotationSet::getSourceHeight, request.sourceHeight())
                            // Explicit set includes null, so a cleared note stays cleared.
                            .set(AnnotationSet::getNote, request.note())
                            .set(AnnotationSet::getRevision, nextRevision)
                            .set(AnnotationSet::getUpdatedAt, now)
            );
            if (updated == 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ANNOTATION_STATE_CONFLICT",
                        "Annotation set changed or is not editable; reload it"
                );
            }
            annotationSet.applyContent(request, nextRevision, now);
        }

        List<PictureAnnotation> annotations = new ArrayList<>(request.shapes().size());
        for (int index = 0; index < request.shapes().size(); index++) {
            annotations.add(PictureAnnotation.create(
                    annotationSet.getId(), request.shapes().get(index), index, now, objectMapper
            ));
        }

        // CAS/creation, removal and replacement all share this transaction.
        pictureAnnotationMapper.delete(Wrappers.<PictureAnnotation>lambdaQuery()
                .eq(PictureAnnotation::getAnnotationSetId, annotationSet.getId()));
        try {
            for (PictureAnnotation annotation : annotations) {
                pictureAnnotationMapper.insert(annotation);
            }
        } catch (DuplicateKeyException exception) {
            ApiException conflict = new ApiException(
                    HttpStatus.CONFLICT,
                    "ANNOTATION_ID_CONFLICT",
                    "A shapeId is already used by another annotation set"
            );
            conflict.initCause(exception);
            // Do not query after the PostgreSQL error: throw and roll back everything.
            throw conflict;
        }

        return AnnotationSetResponse.fromEntity(annotationSet, annotations, objectMapper);
    }

    private AnnotationSet requireAnnotationSet(
            UUID currentUserId,
            UUID pictureId,
            UUID assessmentId
    ) {
        AnnotationSet annotationSet = annotationSetMapper.selectOne(
                Wrappers.<AnnotationSet>lambdaQuery()
                        .eq(AnnotationSet::getPictureId, pictureId)
                        .eq(AnnotationSet::getOwnerUserId, currentUserId)
                        .eq(AnnotationSet::getAssessmentId, assessmentId)
        );
        if (annotationSet == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "ANNOTATION_SET_NOT_FOUND",
                    "Annotation set does not exist"
            );
        }
        return annotationSet;
    }
}
