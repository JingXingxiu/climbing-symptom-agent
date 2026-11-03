package org.javaup.image.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.javaup.image.api.dto.AnnotationSetResponse;
import org.javaup.image.api.dto.PictureUploadStatus;
import org.javaup.image.api.dto.ResolveAgentPicturesRequest;
import org.javaup.image.api.dto.ResolvedAgentPicture;
import org.javaup.image.api.dto.VisualSourceType;
import org.javaup.image.api.problem.ApiException;
import org.javaup.image.entity.AnnotationSet;
import org.javaup.image.entity.PictureAnnotation;
import org.javaup.image.entity.PictureAsset;
import org.javaup.image.mapper.AnnotationSetMapper;
import org.javaup.image.mapper.PictureAnnotationMapper;
import org.javaup.image.mapper.PictureAssetMapper;
import org.javaup.image.storage.MinioObjectUrlSigner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@AllArgsConstructor
public class AgentPictureServiceImpl implements org.javaup.image.service.AgentPictureService {
    private final PictureAssetMapper pictureAssetMapper;
    private final AnnotationSetMapper annotationSetMapper;
    private final PictureAnnotationMapper pictureAnnotationMapper;
    private final MinioObjectUrlSigner urlSigner;
    private final ObjectMapper objectMapper;

    /** 三次批量查询，图片与标注取同一数据库快照；不调用模型，也不请求对象正文。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ResolvedAgentPicture> resolve(UUID currentUserId, ResolveAgentPicturesRequest request) {
        if (currentUserId == null || request == null || request.assessmentId() == null
                || request.pictureIds() == null || request.pictureIds().size() > 12
                || request.pictureIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(request.pictureIds()).size() != request.pictureIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_PICTURES",
                    "Assessment and up to 12 distinct picture IDs are required");
        }
        if (request.pictureIds().isEmpty()) {
            return List.of();
        }

        // 标准图片公共可读，其余图片必须属于当前用户。非法 ID 统一 404。
        List<PictureAsset> pictures = pictureAssetMapper.selectList(Wrappers.<PictureAsset>lambdaQuery()
                .in(PictureAsset::getId, request.pictureIds())
                .isNull(PictureAsset::getDeletedAt)
                .and(scope -> scope.eq(PictureAsset::getSourceType, VisualSourceType.STANDARD_REFERENCE.name())
                        .or().eq(PictureAsset::getOwnerUserId, currentUserId)));
        Map<UUID, PictureAsset> byId = new HashMap<>();
        for (PictureAsset picture : pictures) {
            byId.put(picture.getId(), picture);
        }
        // 也校验返回实体，避免将将来改错查询条件变成越权读取。
        for (UUID pictureId : request.pictureIds()) {
            PictureAsset picture = byId.get(pictureId);
            if (picture == null || picture.getDeletedAt() != null
                    || (!VisualSourceType.STANDARD_REFERENCE.name().equals(picture.getSourceType())
                    && !currentUserId.equals(picture.getOwnerUserId()))) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PICTURE_NOT_FOUND", "Picture does not exist");
            }
            if (!PictureUploadStatus.AVAILABLE.name().equals(picture.getStorageStatus())
                    || picture.getWidthPx() == null || picture.getWidthPx() < 1
                    || picture.getHeightPx() == null || picture.getHeightPx() < 1
                    || picture.getContentType() == null || !picture.getContentType().startsWith("image/")) {
                throw new ApiException(HttpStatus.CONFLICT, "PICTURE_NOT_READY", "Picture is not ready for analysis");
            }
        }

        List<AnnotationSet> sets = annotationSetMapper.selectList(Wrappers.<AnnotationSet>lambdaQuery()
                .eq(AnnotationSet::getAssessmentId, request.assessmentId())
                .eq(AnnotationSet::getOwnerUserId, currentUserId)
                .in(AnnotationSet::getPictureId, request.pictureIds()));
        Map<UUID, AnnotationSet> setByPicture = new HashMap<>();
        for (AnnotationSet set : sets) {
            setByPicture.put(set.getPictureId(), set);
        }
        Map<UUID, List<PictureAnnotation>> shapesBySet = new HashMap<>();
        if (!sets.isEmpty()) {
            List<PictureAnnotation> shapes = pictureAnnotationMapper.selectList(
                    Wrappers.<PictureAnnotation>lambdaQuery()
                            .in(PictureAnnotation::getAnnotationSetId, sets.stream().map(AnnotationSet::getId).toList())
                            .orderByAsc(PictureAnnotation::getSortOrder));
            for (PictureAnnotation shape : shapes) {
                shapesBySet.computeIfAbsent(shape.getAnnotationSetId(), ignored -> new ArrayList<>()).add(shape);
            }
        }

        List<ResolvedAgentPicture> resolved = new ArrayList<>();
        for (UUID pictureId : request.pictureIds()) {
            PictureAsset picture = byId.get(pictureId);
            AnnotationSet set = setByPicture.get(pictureId);
            JsonNode annotation = set == null ? null : objectMapper.valueToTree(
                    AnnotationSetResponse.fromEntity(set, shapesBySet.getOrDefault(set.getId(), List.of()), objectMapper));
            JsonNode viewState = picture.getViewState() == null ? null
                    : objectMapper.readTree(picture.getViewState().getValue());
            URI originalUrl;
            try {
                originalUrl = urlSigner.signGet(picture.getBucketName(), picture.getObjectKey());
                if (originalUrl == null || originalUrl.getHost() == null
                        || !("http".equals(originalUrl.getScheme()) || "https".equals(originalUrl.getScheme()))) {
                    throw new IllegalStateException("Missing signed image URL");
                }
            } catch (RuntimeException exception) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PICTURE_URL_UNAVAILABLE",
                        "Picture access is temporarily unavailable");
            }
            resolved.add(new ResolvedAgentPicture(pictureId, picture.getSourceType(), picture.getContentType(),
                    picture.getWidthPx(), picture.getHeightPx(), viewState, annotation, originalUrl));
        }
        return List.copyOf(resolved);
    }
}
