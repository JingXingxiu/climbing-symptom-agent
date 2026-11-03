package org.javaup.history.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import feign.FeignException;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

import lombok.AllArgsConstructor;
import org.javaup.history.api.dto.*;
import org.javaup.history.api.dto.HistoryEnums.AssessmentStatus;
import org.javaup.history.api.dto.HistoryEnums.RecommendationLevel;
import org.javaup.history.api.dto.HistoryEnums.SymptomType;
import org.javaup.history.api.problem.ApiException;
import org.javaup.history.client.ImageClient;
import org.javaup.history.entity.Assessment;
import org.javaup.history.entity.AssessmentSymptom;
import org.javaup.history.entity.AssessmentResult;
import org.javaup.history.mapper.AssessmentMapper;
import org.javaup.history.mapper.AssessmentAnswerMapper;
import org.javaup.history.service.AssessmentAnswerService;
import org.javaup.history.service.AssessmentResultService;
import org.javaup.history.service.AssessmentService;
import org.javaup.history.service.AssessmentSymptomService;
import org.javaup.history.service.AssessmentVisualService;
import org.javaup.history.service.SymptomLocationService;
import org.javaup.history.api.dto.HistoryEnums.AssessmentAnswerSource;
import org.javaup.history.api.dto.HistoryEnums.FingerCode;
import org.javaup.history.api.dto.HistoryEnums.HandSide;
import org.javaup.history.api.dto.HistoryEnums.JointCode;
import org.javaup.history.entity.AssessmentAnswer;
import org.javaup.history.entity.AssessmentVisual;
import org.javaup.history.entity.SymptomLocation;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

@Service
@AllArgsConstructor
public class AssessmentServiceImpl
        extends ServiceImpl<AssessmentMapper, Assessment>
        implements AssessmentService {

    private final AssessmentAnswerService assessmentAnswerService;
    private final AssessmentResultService assessmentResultService;
    private final AssessmentSymptomService assessmentSymptomService;
    private final AssessmentVisualService assessmentVisualService;
    private final SymptomLocationService symptomLocationService;
    private final AssessmentMapper assessmentMapper;
    private final ObjectMapper objectMapper;
    private final ImageClient imageClient;
    private final TransactionTemplate assessmentWriteTransaction;
    private final AssessmentAnswerMapper assessmentAnswerMapper;

    @Override
    @Transactional
    public AssessmentResponse create(
            UUID currentUserId,
            UUID idempotencyKey,
            CreateAssessmentRequest request
    ) {
        InitialSymptomDetails input = request.initialInput();
        Assessment entity= Assessment.create(currentUserId,idempotencyKey,input);

        int result=assessmentMapper.insertIfAbsent(entity);

        /// 相同用户使用相同幂等键重试
        if (result == 0) {
            Assessment existing = lambdaQuery()
                    .eq(Assessment::getUserId, currentUserId)
                    .eq(Assessment::getCreateIdempotencyKey, idempotencyKey)
                    .one();

            if (existing == null) {
                throw new IllegalStateException(
                        "Idempotent assessment exists but cannot be loaded"
                );
            }

            return buildResponse(existing);
        }

        if (input != null
                && input.symptomTypes() != null
                && !input.symptomTypes().isEmpty()) {

            List<AssessmentSymptom> symptoms = input.symptomTypes()
                    .stream()
                    .distinct()
                    .map(type -> {
                        AssessmentSymptom symptom = new AssessmentSymptom();
                        symptom.setAssessmentId(entity.getId());
                        symptom.setSymptomCode(type.name());
                        return symptom;
                    })
                    .toList();

            boolean saved = assessmentSymptomService.saveBatch(symptoms);

            if (!saved) {
                throw new IllegalStateException(
                        "Failed to save assessment symptoms"
                );
            }
        }

        Assessment persisted = getById(entity.getId());
        if (persisted == null) {
            throw new IllegalStateException("Created assessment cannot be loaded");
        }

        return buildResponse(persisted);
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentPageResponse list(
            UUID currentUserId,
            AssessmentStatus status,
            String cursor,
            int limit
    ) {
        if (limit < 1 || limit > 50) {
            throw new ApiException(
                    HttpStatusCode.valueOf(400),
                    "INVALID_PAGE_LIMIT",
                    "limit must be between 1 and 50"
            );
        }

        AssessmentCursor decodedCursor = decodeCursor(cursor);

        var query = lambdaQuery()
                .eq(Assessment::getUserId, currentUserId);

        if (status != null) {
            query.eq(Assessment::getStatus, status.name());
        }

        if (decodedCursor != null) {
            query.and(condition -> condition
                    .lt(Assessment::getUpdatedAt, decodedCursor.updatedAt())
                    .or(sameTime -> sameTime
                            .eq(Assessment::getUpdatedAt, decodedCursor.updatedAt())
                            .lt(Assessment::getId, decodedCursor.assessmentId())));
        }

        List<Assessment> fetched = query
                .orderByDesc(Assessment::getUpdatedAt)
                .orderByDesc(Assessment::getId)
                .last("LIMIT " + (limit + 1))
                .list();

        boolean hasMore = fetched.size() > limit;
        List<Assessment> page = hasMore
                ? fetched.subList(0, limit)
                : fetched;

        if (page.isEmpty()) {
            return new AssessmentPageResponse(List.of(), null, false);
        }

        List<UUID> assessmentIds = page.stream()
                .map(Assessment::getId)
                .toList();

        Map<UUID, String> locationLabels = new HashMap<>();
        symptomLocationService.lambdaQuery()
                .in(SymptomLocation::getAssessmentId, assessmentIds)
                .orderByDesc(SymptomLocation::getPrimary)
                .orderByAsc(SymptomLocation::getCreatedAt)
                .list()
                .forEach(location -> locationLabels.putIfAbsent(
                        location.getAssessmentId(),
                        location.getRegionLabel()
                ));

        Map<UUID, RecommendationLevel> recommendationLevels = new HashMap<>();
        assessmentResultService.lambdaQuery()
                .in(AssessmentResult::getAssessmentId, assessmentIds)
                .list()
                .forEach(result -> recommendationLevels.put(
                        result.getAssessmentId(),
                        RecommendationLevel.valueOf(result.getRecommendationLevel())
                ));

        List<AssessmentSummary> items = page.stream()
                .map(entity -> new AssessmentSummary(
                        entity.getId(),
                        AssessmentStatus.valueOf(entity.getStatus()),
                        entity.getHandSide() == null
                                ? null
                                : HandSide.valueOf(entity.getHandSide()),
                        locationLabels.get(entity.getId()),
                        entity.getTriggerContext(),
                        recommendationLevels.get(entity.getId()),
                        entity.getUpdatedAt().toInstant()
                ))
                .toList();

        String nextCursor = hasMore
                ? encodeCursor(page.getLast())
                : null;

        return new AssessmentPageResponse(items, nextCursor, hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentResponse get(UUID currentUserId, UUID assessmentId) {
        return buildResponse(
                requireOwnedAssessment(currentUserId, assessmentId)
        );
    }

    private AssessmentResponse buildResponse(Assessment entity) {
        UUID assessmentId = entity.getId();

        List<SymptomType> symptomTypes = assessmentSymptomService.lambdaQuery()
                .eq(AssessmentSymptom::getAssessmentId, assessmentId)
                .orderByAsc(AssessmentSymptom::getSymptomCode)
                .list()
                .stream()
                .map(AssessmentSymptom::getSymptomCode)
                .map(SymptomType::valueOf)
                .toList();

        List<SymptomLocationResponse> locations = symptomLocationService.lambdaQuery()
                .eq(SymptomLocation::getAssessmentId, assessmentId)
                .orderByDesc(SymptomLocation::getPrimary)
                .orderByAsc(SymptomLocation::getCreatedAt)
                .list()
                .stream()
                .map(SymptomLocationResponse::fromEntity)
                .toList();

        List<VisualReference> visuals = assessmentVisualService.lambdaQuery()
                .eq(AssessmentVisual::getAssessmentId, assessmentId)
                .orderByAsc(AssessmentVisual::getSortOrder)
                .orderByAsc(AssessmentVisual::getCreatedAt)
                .list()
                .stream()
                .map(VisualReference::fromEntity)
                .toList();

        List<AssessmentAnswerResponse> answers = assessmentAnswerService.lambdaQuery()
                .eq(AssessmentAnswer::getAssessmentId, assessmentId)
                .orderByAsc(AssessmentAnswer::getSequenceNo)
                .list()
                .stream()
                .map(answer -> AssessmentAnswerResponse.fromEntity(answer, objectMapper))
                .toList();

        return AssessmentResponse.fromEntity(
                entity,
                symptomTypes,
                locations,
                visuals,
                answers
        );
    }

    @Override
    @Transactional
    public AssessmentResponse updateBasics(
            UUID currentUserId,
            UUID assessmentId,
            UpdateAssessmentBasicsRequest request
    ) {
        InitialSymptomDetails input = request.initialInput();
        if (input == null) {
            throw new ApiException(
                    HttpStatusCode.valueOf(400),
                    "INITIAL_INPUT_REQUIRED",
                    "initialInput is required when updating assessment basics"
            );
        }

        Assessment existing = requireOwnedAssessment(
                currentUserId,
                assessmentId
        );

        AssessmentStatus currentStatus =
                AssessmentStatus.valueOf(existing.getStatus());

        if (currentStatus.isTerminal()) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_NOT_EDITABLE",
                    "Completed or cancelled assessment cannot be modified"
            );
        }

        if (!existing.getVersion().equals(request.version())) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_VERSION_CONFLICT",
                    "Assessment has been modified; reload and retry"
            );
        }

        int nextVersion = request.version() + 1;
        OffsetDateTime now = OffsetDateTime.now();

        existing.applyBasics(
                input,
                request.currentStepKey(),
                nextVersion,
                now
        );

        var update = lambdaUpdate()
                .eq(Assessment::getId, assessmentId)
                .eq(Assessment::getUserId, currentUserId)
                .eq(Assessment::getVersion, request.version())
                .eq(Assessment::getStatus, currentStatus.name());

        boolean updated = update
                .set(Assessment::getHandSide, existing.getHandSide())
                .set(Assessment::getOnsetKind, existing.getOnsetKind())
                .set(Assessment::getOnsetAmount, existing.getOnsetAmount())
                .set(Assessment::getOnsetDate, existing.getOnsetDate())
                .set(Assessment::getOnsetText, existing.getOnsetText())
                .set(Assessment::getTriggerContext, existing.getTriggerContext())
                .set(Assessment::getNormalUseImpact, existing.getNormalUseImpact())
                .set(Assessment::getAdditionalNote, existing.getAdditionalNote())
                .set(Assessment::getCurrentStepKey, existing.getCurrentStepKey())
                .set(Assessment::getStatus, existing.getStatus())
                .set(Assessment::getVersion, existing.getVersion())
                .set(Assessment::getLastSavedAt, existing.getLastSavedAt())
                .set(Assessment::getUpdatedAt, existing.getUpdatedAt())
                .update();

        // 查询后到 UPDATE 前，仍可能有另一个请求抢先完成更新。
        if (!updated) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_VERSION_CONFLICT",
                    "Assessment has been modified; reload and retry"
            );
        }

        // initialInput 是完整替换，所以症状集合也整体替换。
        assessmentSymptomService.lambdaUpdate()
                .eq(
                        AssessmentSymptom::getAssessmentId,
                        assessmentId
                )
                .remove();

        if (input.symptomTypes() != null
                && !input.symptomTypes().isEmpty()) {

            List<AssessmentSymptom> symptoms = input.symptomTypes()
                    .stream()
                    .distinct()
                    .map(type -> {
                        AssessmentSymptom symptom =
                                new AssessmentSymptom();
                        symptom.setAssessmentId(assessmentId);
                        symptom.setSymptomCode(type.name());
                        return symptom;
                    })
                    .toList();

            boolean symptomsSaved =
                    assessmentSymptomService.saveBatch(symptoms);

            if (!symptomsSaved) {
                throw new IllegalStateException(
                        "Failed to save assessment symptoms"
                );
            }
        }

        return buildResponse(existing);

    }

    private Assessment requireOwnedAssessment(
            UUID currentUserId,
            UUID assessmentId
    ) {
        Assessment entity = lambdaQuery()
                .eq(Assessment::getId, assessmentId)
                .eq(Assessment::getUserId, currentUserId)
                .one();

        if (entity == null) {
            throw new ApiException(
                    HttpStatusCode.valueOf(404),
                    "ASSESSMENT_NOT_FOUND",
                    "Assessment does not exist"
            );
        }

        return entity;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssessmentResumeResponse resume(UUID currentUserId, UUID assessmentId) {
        Assessment existing = requireOwnedAssessment(currentUserId, assessmentId);
        if (AssessmentStatus.CANCELLED.name().equals(existing.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_CANCELLED", "Cancelled assessment cannot be resumed"
            );
        }
        if (AssessmentStatus.COMPLETED.name().equals(existing.getStatus())) {
            // Normalize only the response; a read operation never updates navigation.
            existing.setCurrentStepKey(HistoryEnums.CurrentStepKey.RESULT.name());
        }
        AssessmentResponse response = buildResponse(existing);
        // No real Agent session is persisted or invented in this implementation.
        return new AssessmentResumeResponse(response, response.currentStepKey(), null);
    }

    @Override
    @Transactional
    public List<SymptomLocationResponse> replaceLocations(
            UUID currentUserId,
            UUID assessmentId,
            ReplaceSymptomLocationsRequest request
    ) {
        Assessment existing =
                requireOwnedAssessment(currentUserId, assessmentId);

        AssessmentStatus status =
                AssessmentStatus.valueOf(existing.getStatus());

        if (status.isTerminal()) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_NOT_EDITABLE",
                    "Completed or cancelled assessment cannot be modified"
            );
        }

        if (existing.getHandSide() == null) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_HAND_SIDE_REQUIRED",
                    "Select the assessment hand side before saving locations"
            );
        }

        if (request == null || request.symptomLocations() == null) {
            throw new ApiException(
                    HttpStatusCode.valueOf(400),
                    "SYMPTOM_LOCATIONS_REQUIRED",
                    "symptomLocations is required"
            );
        }

        HandSide assessmentHandSide =
                HandSide.valueOf(existing.getHandSide());

        String expectedRegionPrefix =
                "hand."
                        + assessmentHandSide.name()
                        .toLowerCase(Locale.ROOT)
                        + ".";

        Set<String> regionIds = new HashSet<>();

        for (SymptomLocationInput input : request.symptomLocations()) {
            if (input == null) {
                throw new ApiException(
                        HttpStatusCode.valueOf(400),
                        "INVALID_SYMPTOM_LOCATION",
                        "symptomLocations must not contain null"
                );
            }

            if (input.handSide() != assessmentHandSide
                    || input.regionId() == null
                    || !input.regionId().startsWith(expectedRegionPrefix)) {
                throw new ApiException(
                        HttpStatusCode.valueOf(400),
                        "LOCATION_HAND_SIDE_MISMATCH",
                        "Every location must match the assessment hand side"
                );
            }

            if (!regionIds.add(input.regionId())) {
                throw new ApiException(
                        HttpStatusCode.valueOf(400),
                        "DUPLICATE_SYMPTOM_LOCATION",
                        "Duplicate regionId: " + input.regionId()
                );
            }
        }

        OffsetDateTime now = OffsetDateTime.now();

        /*
          除了更新时间，这条 UPDATE 还会锁住 Assessment 根记录，
          让同一个 Assessment 的 replace/clear 串行执行。
         */
        boolean parentTouched = lambdaUpdate()
                .eq(Assessment::getId, assessmentId)
                .eq(Assessment::getUserId, currentUserId)
                .eq(
                        Assessment::getHandSide,
                        assessmentHandSide.name()
                )
                .eq(Assessment::getStatus, status.name())
                .lt(Assessment::getVersion, Integer.MAX_VALUE)
                .setSql("version = version + 1")
                .set(Assessment::getLastSavedAt, now)
                .set(Assessment::getUpdatedAt, now)
                .update();

        if (!parentTouched) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_STATE_CONFLICT",
                    "Assessment state changed; reload and retry"
            );
        }

        symptomLocationService.lambdaUpdate()
                .eq(
                        SymptomLocation::getAssessmentId,
                        assessmentId
                )
                .remove();

        List<SymptomLocation> locations =
                new ArrayList<>(request.symptomLocations().size());

        for (int index = 0;
             index < request.symptomLocations().size();
             index++) {

            SymptomLocation location = SymptomLocation.create(
                    assessmentId,
                    assessmentHandSide.name(),
                    request.symptomLocations().get(index),
                    index == 0,
                    now
            );

            locations.add(location);
        }

        if (!locations.isEmpty()
                && !symptomLocationService.saveBatch(locations)) {
            throw new IllegalStateException(
                    "Failed to save symptom locations"
            );
        }

        return locations.stream()
                .map(SymptomLocationResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional
    public void clearLocations(
            UUID currentUserId,
            UUID assessmentId
    ) {
        Assessment existing =
                requireOwnedAssessment(currentUserId, assessmentId);

        AssessmentStatus status =
                AssessmentStatus.valueOf(existing.getStatus());

        if (status.isTerminal()) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_NOT_EDITABLE",
                    "Completed or cancelled assessment cannot be modified"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();

        /*
         * 先锁住根记录，再删除位置，避免 replace 和 clear
         * 同时执行时互相覆盖。
         */
        boolean parentTouched = lambdaUpdate()
                .eq(Assessment::getId, assessmentId)
                .eq(Assessment::getUserId, currentUserId)
                .eq(Assessment::getStatus, status.name())
                .lt(Assessment::getVersion, Integer.MAX_VALUE)
                .setSql("version = version + 1")
                .set(Assessment::getLastSavedAt, now)
                .set(Assessment::getUpdatedAt, now)
                .update();

        if (!parentTouched) {
            throw new ApiException(
                    HttpStatusCode.valueOf(409),
                    "ASSESSMENT_STATE_CONFLICT",
                    "Assessment state changed; reload and retry"
            );
        }

        /*
         * 返回 false 仅表示原来就没有位置。
         * DELETE 重复执行仍然算成功，不需要抛异常。
         */
        symptomLocationService.lambdaUpdate()
                .eq(
                        SymptomLocation::getAssessmentId,
                        assessmentId
                )
                .remove();
    }

    @Override
    public List<VisualReference> replaceVisuals(
            UUID currentUserId,
            UUID assessmentId,
            ReplaceVisualReferencesRequest request
    ) {
        if (request == null
                || request.pictureIds() == null
                || request.pictureIds().size() > 12
                || request.version() == null
                || request.version() < 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_VISUAL_REFERENCES",
                    "pictureIds and a positive version are required; maximum 12 pictures"
            );
        }

        Assessment existing = requireOwnedAssessment(currentUserId, assessmentId);
        AssessmentStatus currentStatus = AssessmentStatus.valueOf(existing.getStatus());
        if (currentStatus.isTerminal()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ASSESSMENT_NOT_EDITABLE",
                    "Completed or cancelled assessment cannot be modified"
            );
        }
        if (!existing.getVersion().equals(request.version())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ASSESSMENT_VERSION_CONFLICT",
                    "Assessment has been modified; reload and retry"
            );
        }

        Set<UUID> pictureIds = new HashSet<>();
        for (UUID pictureId : request.pictureIds()) {
            if (pictureId == null || !pictureIds.add(pictureId)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PICTURE_IDS",
                        "pictureIds must not contain null or duplicate IDs"
                );
            }
        }

        List<AssessmentVisual> visuals = new ArrayList<>(request.pictureIds().size());

        // Controller calls this method without an outer transaction. Validate remotely
        // before opening the local write transaction, and persist IDs, never signed URLs.
        try {
            if (!request.pictureIds().isEmpty()) {
                List<ImageClient.ResolvedVisualReference> resolved =
                        imageClient.resolveVisualReferences(currentUserId,
                                new ImageClient.ResolveVisualReferencesRequest(
                                        assessmentId, request.pictureIds()));

                if (resolved == null || resolved.size() != request.pictureIds().size()) {
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "INVALID_IMAGE_RESPONSE",
                            "Image service returned an incomplete picture batch"
                    );
                }

                Map<UUID, ImageClient.ResolvedVisualReference> byPictureId = new HashMap<>();
                for (ImageClient.ResolvedVisualReference reference : resolved) {
                    if (reference == null
                            || reference.pictureId() == null
                            || reference.sourceType() == null
                            || !pictureIds.contains(reference.pictureId())
                            || byPictureId.putIfAbsent(reference.pictureId(), reference) != null) {
                        throw new ApiException(
                                HttpStatus.BAD_GATEWAY,
                                "INVALID_IMAGE_RESPONSE",
                                "Image service returned invalid picture references"
                        );
                    }
                }

                // Persist the caller's order rather than relying on a remote response's order.
                OffsetDateTime resolvedAt = OffsetDateTime.now();
                for (int index = 0; index < request.pictureIds().size(); index++) {
                    UUID pictureId = request.pictureIds().get(index);
                    ImageClient.ResolvedVisualReference reference = byPictureId.get(pictureId);
                    visuals.add(AssessmentVisual.create(
                            assessmentId, pictureId, reference.annotationSetId(), reference.sourceType(),
                            index, resolvedAt
                    ));
                }
            }
        } catch (FeignException.NotFound exception) {
            ApiException failure = new ApiException(
                    HttpStatus.NOT_FOUND,
                    "IMAGE_REFERENCE_NOT_FOUND",
                    "Referenced picture or annotation resource is not accessible"
            );
            failure.initCause(exception);
            throw failure;
        } catch (FeignException.Conflict exception) {
            ApiException failure = new ApiException(
                    HttpStatus.CONFLICT,
                    "PICTURE_NOT_AVAILABLE",
                    "A referenced picture is not available"
            );
            failure.initCause(exception);
            throw failure;
        } catch (RetryableException exception) {
            ApiException failure = new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "IMAGE_SERVICE_UNAVAILABLE",
                    "Image service is temporarily unavailable"
            );
            failure.initCause(exception);
            throw failure;
        } catch (FeignException exception) {
            ApiException failure = new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "IMAGE_SERVICE_ERROR",
                    "Failed to validate image references"
            );
            failure.initCause(exception);
            throw failure;
        }

        return assessmentWriteTransaction.execute(transactionStatus -> {
            OffsetDateTime now = OffsetDateTime.now();
            if (request.version() == Integer.MAX_VALUE) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ASSESSMENT_VERSION_EXHAUSTED",
                        "Assessment version cannot be incremented"
                );
            }

            boolean updated = lambdaUpdate()
                    .eq(Assessment::getId, assessmentId)
                    .eq(Assessment::getUserId, currentUserId)
                    .eq(Assessment::getVersion, request.version())
                    .eq(Assessment::getStatus, currentStatus.name())
                    .set(Assessment::getVersion, request.version() + 1)
                    .set(Assessment::getLastSavedAt, now)
                    .set(Assessment::getUpdatedAt, now)
                    .update();
            if (!updated) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ASSESSMENT_VERSION_CONFLICT",
                        "Assessment changed during image validation; reload and retry"
                );
            }

            assessmentVisualService.lambdaUpdate()
                    .eq(AssessmentVisual::getAssessmentId, assessmentId)
                    .remove();
            if (!visuals.isEmpty() && !assessmentVisualService.saveBatch(visuals)) {
                throw new IllegalStateException("Failed to save assessment visual references");
            }

            return visuals.stream().map(VisualReference::fromEntity).toList();
        });
    }

    @Override
    @Transactional
    public AssessmentAnswerResponse appendAnswer(
            UUID currentUserId,
            UUID assessmentId,
            AppendAssessmentAnswerRequest request
    ) {
        if (request == null || request.clientAnswerId() == null
                || request.source() == null
                || request.questionKey() == null
                || !request.questionKey().matches("^[a-z][a-z0-9_.-]{1,79}$")
                || (request.questionText() != null && request.questionText().length() > 500)
                || request.answer() == null || request.answer().isNull() || request.answer().isMissingNode()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_ASSESSMENT_ANSWER", "Answer metadata and non-null JSON are required"
            );
        }

        Assessment existing = requireOwnedAssessmentForUpdate(currentUserId, assessmentId);
        AssessmentAnswer saved = assessmentAnswerService.lambdaQuery()
                .eq(AssessmentAnswer::getAssessmentId, assessmentId)
                .eq(AssessmentAnswer::getClientAnswerId, request.clientAnswerId())
                .one();
        if (saved != null) {
            // A replay is read-only, even if the assessment has since completed.
            return replayAnswer(saved, request);
        }
        AssessmentStatus status = AssessmentStatus.valueOf(existing.getStatus());
        if (status.isTerminal()) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_NOT_EDITABLE", "Completed or cancelled assessment cannot accept new answers"
            );
        }
        if (status == AssessmentStatus.DRAFT
                || existing.getSubmittedAt() == null || existing.getLastSubmitVersion() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_NOT_SUBMITTED", "Submit the assessment before adding answers"
            );
        }
        if (existing.getVersion() == Integer.MAX_VALUE) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_VERSION_EXHAUSTED", "Assessment version cannot be incremented"
            );
        }
        if (assessmentAnswerService.lambdaQuery()
                .eq(AssessmentAnswer::getAssessmentId, assessmentId)
                .eq(AssessmentAnswer::getSequenceNo, request.sequenceNumber())
                .exists()) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ANSWER_SEQUENCE_CONFLICT", "sequenceNumber is already used by another answer"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();
        AssessmentAnswer answer = AssessmentAnswer.create(assessmentId, request, now, objectMapper);
        int inserted;
        try {
            inserted = assessmentAnswerMapper.insertIfAbsent(answer);
        } catch (DuplicateKeyException exception) {
            ApiException conflict = new ApiException(
                    HttpStatus.CONFLICT, "ANSWER_SEQUENCE_CONFLICT", "Answer identity or sequence is already in use"
            );
            conflict.initCause(exception);
            // Throw and roll back; never query inside a failed PostgreSQL transaction.
            throw conflict;
        }
        if (inserted == 0) {
            AssessmentAnswer duplicate = assessmentAnswerService.lambdaQuery()
                    .eq(AssessmentAnswer::getAssessmentId, assessmentId)
                    .eq(AssessmentAnswer::getClientAnswerId, request.clientAnswerId())
                    .one();
            if (duplicate == null) {
                throw new IllegalStateException("Idempotent answer exists but cannot be loaded");
            }
            return replayAnswer(duplicate, request);
        }

        int previousVersion = existing.getVersion();
        existing.markAnswerSaved(previousVersion + 1, now);
        boolean updated = lambdaUpdate()
                .eq(Assessment::getId, assessmentId)
                .eq(Assessment::getUserId, currentUserId)
                .eq(Assessment::getVersion, previousVersion)
                .eq(Assessment::getStatus, status.name())
                .set(Assessment::getVersion, existing.getVersion())
                .set(Assessment::getLastSavedAt, now)
                .set(Assessment::getUpdatedAt, now)
                .update();
        if (!updated) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_STATE_CONFLICT", "Assessment changed while saving its answer"
            );
        }
        return AssessmentAnswerResponse.fromEntity(answer, objectMapper);
    }

    @Override
    @Transactional
    public AssessmentResponse submit(
            UUID currentUserId,
            UUID assessmentId,
            SubmitAssessmentRequest request
    ) {
        if (request == null || request.version() == null || request.version() < 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_SUBMISSION", "A positive Assessment version is required"
            );
        }
        Assessment existing = requireOwnedAssessmentForUpdate(currentUserId, assessmentId);
        AssessmentStatus status = AssessmentStatus.valueOf(existing.getStatus());
        if (status == AssessmentStatus.CANCELLED) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_NOT_EDITABLE", "Cancelled assessment cannot be submitted"
            );
        }

        // Navigation is client-editable. Only a server-persisted receipt proves a submit.
        boolean hasReceipt = existing.getSubmittedAt() != null && existing.getLastSubmitVersion() != null;
        if (hasReceipt && request.version().equals(existing.getLastSubmitVersion())) {
            return buildResponse(existing);
        }
        if (!request.version().equals(existing.getVersion())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_VERSION_CONFLICT", "Assessment has been modified; reload and retry"
            );
        }
        // Freshly fetched version, but no edits since the last submit: still a no-op.
        if (hasReceipt && existing.getVersion() == existing.getLastSubmitVersion() + 1) {
            return buildResponse(existing);
        }
        if (status.isTerminal()) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_NOT_EDITABLE", "Completed assessment cannot be submitted again"
            );
        }
        if (existing.getVersion() == Integer.MAX_VALUE) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_VERSION_EXHAUSTED", "Assessment version cannot be incremented"
            );
        }

        if (existing.getHandSide() == null || existing.getOnsetKind() == null
                || existing.getTriggerContext() == null || existing.getTriggerContext().isBlank()
                || existing.getNormalUseImpact() == null) {
            throw new ApiException(
                    HttpStatusCode.valueOf(422), "ASSESSMENT_INPUT_INCOMPLETE",
                    "Hand side, onset, activity context and normal-use impact are required"
            );
        }
        HistoryEnums.OnsetKind onset = HistoryEnums.OnsetKind.valueOf(existing.getOnsetKind());
        if ((onset == HistoryEnums.OnsetKind.DAYS_AGO
                || onset == HistoryEnums.OnsetKind.WEEKS_AGO
                || onset == HistoryEnums.OnsetKind.MONTHS_AGO)
                && (existing.getOnsetAmount() == null || existing.getOnsetAmount() < 1)) {
            throw new ApiException(
                    HttpStatusCode.valueOf(422), "ASSESSMENT_ONSET_INCOMPLETE", "Relative onset requires a positive amount"
            );
        }

        if (onset == HistoryEnums.OnsetKind.EXACT_DATE
                && (existing.getOnsetDate() == null
                || existing.getOnsetDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))) {
            throw new ApiException(
                    HttpStatusCode.valueOf(422), "ASSESSMENT_ONSET_INCOMPLETE", "Exact onset requires a non-future date"
            );
        }
        boolean hasLocation = symptomLocationService.lambdaQuery()
                .eq(SymptomLocation::getAssessmentId, assessmentId).exists();
        boolean hasVisual = hasLocation || assessmentVisualService.lambdaQuery()
                .eq(AssessmentVisual::getAssessmentId, assessmentId).exists();
        if (!hasVisual) {
            throw new ApiException(
                    HttpStatusCode.valueOf(422), "ASSESSMENT_LOCATION_REQUIRED", "At least one coarse location or picture is required"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();
        existing.markSubmitted(request.version(), now);
        boolean updated = lambdaUpdate()
                .eq(Assessment::getId, assessmentId)
                .eq(Assessment::getUserId, currentUserId)
                .eq(Assessment::getVersion, request.version())
                .eq(Assessment::getStatus, status.name())
                .set(Assessment::getStatus, existing.getStatus())
                .set(Assessment::getCurrentStepKey, existing.getCurrentStepKey())
                .set(Assessment::getSubmittedAt, existing.getSubmittedAt())
                .set(Assessment::getLastSubmitVersion, existing.getLastSubmitVersion())
                .set(Assessment::getVersion, existing.getVersion())
                .set(Assessment::getLastSavedAt, now)
                .set(Assessment::getUpdatedAt, now)
                .update();
        if (!updated) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ASSESSMENT_VERSION_CONFLICT", "Assessment changed during submission"
            );
        }
        // Do not call AI, manufacture a session/result, or mark this COMPLETED.
        return buildResponse(existing);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssessmentResultResponse getResult(UUID currentUserId, UUID assessmentId) {
        Assessment existing = requireOwnedAssessment(currentUserId, assessmentId);
        if (!AssessmentStatus.COMPLETED.name().equals(existing.getStatus())) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "ASSESSMENT_RESULT_NOT_READY", "Assessment has no finalized result yet"
            );
        }
        AssessmentResult result = assessmentResultService.getById(assessmentId);
        if (result == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "ASSESSMENT_RESULT_NOT_FOUND", "Assessment result does not exist"
            );
        }
        return AssessmentResultResponse.fromEntity(result, objectMapper);
    }

    private Assessment requireOwnedAssessmentForUpdate(UUID currentUserId, UUID assessmentId) {
        Assessment existing = lambdaQuery()
                .eq(Assessment::getId, assessmentId)
                .eq(Assessment::getUserId, currentUserId)
                .last("FOR UPDATE")
                .one();
        if (existing == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment does not exist");
        }
        return existing;
    }

    private AssessmentAnswerResponse replayAnswer(AssessmentAnswer saved, AppendAssessmentAnswerRequest request) {
        boolean same = Objects.equals(saved.getSequenceNo(), request.sequenceNumber())
                && Objects.equals(saved.getQuestionKey(), request.questionKey())
                && Objects.equals(saved.getQuestionText(), request.questionText())
                && Objects.equals(saved.getAnswerSource(), request.source().name())
                && Objects.equals(objectMapper.readTree(saved.getAnswer().getValue()), request.answer());
        if (!same) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ANSWER_IDEMPOTENCY_KEY_REUSED", "clientAnswerId cannot be reused for different answer content"
            );
        }
        return AssessmentAnswerResponse.fromEntity(saved, objectMapper);
    }

    private String encodeCursor(Assessment entity) {
        String value = entity.getUpdatedAt() + "|" + entity.getId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private AssessmentCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
            int separator = value.lastIndexOf('|');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Malformed cursor");
            }

            return new AssessmentCursor(
                    OffsetDateTime.parse(value.substring(0, separator)),
                    UUID.fromString(value.substring(separator + 1))
            );
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatusCode.valueOf(400),
                    "INVALID_CURSOR",
                    "cursor is invalid"
            );
        }
    }

    private record AssessmentCursor(
            OffsetDateTime updatedAt,
            UUID assessmentId
    ) {
    }
}
