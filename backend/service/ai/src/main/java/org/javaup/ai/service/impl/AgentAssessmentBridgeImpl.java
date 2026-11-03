package org.javaup.ai.service.impl;

import static org.javaup.ai.api.problem.AIErrorCode.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.api.dto.AgentOutcomeResponse;
import org.javaup.ai.api.problem.AIErrorCode;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.client.AgentHistoryClient;
import org.javaup.ai.client.AgentImageClient;
import org.javaup.ai.model.AgentModelRequest;
import org.javaup.ai.service.AgentAssessmentBridge;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class AgentAssessmentBridgeImpl implements AgentAssessmentBridge {

    private final AgentHistoryClient historyClient;
    private final AgentImageClient imageClient;
    private final ObjectMapper objectMapper;

    @Override
    public Snapshot loadSnapshot(
            UUID userId,
            UUID assessmentId
    ) {
        AgentHistoryClient.InputResponse input;

        try {
            input = historyClient.getInput(userId, assessmentId);
        } catch (FeignException exception) {
            throw upstreamFailure(exception, ASSESSMENT_INPUT_UNAVAILABLE);
        }

        if (input == null
                || !assessmentId.equals(input.assessmentId())
                || !userId.equals(input.userId())
                || input.assessmentVersion() < 1
                || input.submittedAt() == null
                || input.assessmentInputFingerprint() == null
                || !input.assessmentInputFingerprint().matches("^[0-9a-f]{64}$")
                || input.assessment() == null
                || !input.assessment().isObject()) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    INVALID_ASSESSMENT_INPUT,
                    "History did not return a submitted assessment owned by this user"
            );
        }

        JsonNode assessment = input.assessment();
        List<UUID> ids = pictureIds(assessment.path("visualReferences"));
        List<AgentImageClient.PictureResponse> pictures = resolveBatch(userId, assessmentId, ids);

        // 只复制业务输入，不把 History 的页面状态、旧 Agent 答案和临时 URL 混进新任务。
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("assessmentId", assessmentId.toString());
        snapshot.put("assessmentVersion", input.assessmentVersion());
        snapshot.put("assessmentInputFingerprint", input.assessmentInputFingerprint());
        snapshot.set("initialInput", assessment.path("initialInput").deepCopy());
        snapshot.set("symptomLocations", assessment.path("symptomLocations").deepCopy());
        snapshot.set("visualReferences", assessment.path("visualReferences").deepCopy());

        var pictureSnapshots = snapshot.putArray("pictures");
        Map<UUID, JsonNode> visualReferences = new HashMap<>();

        for (JsonNode reference : assessment.path("visualReferences")) {
            visualReferences.put(UUID.fromString(reference.path("pictureId").asText()), reference);
        }

        for (AgentImageClient.PictureResponse picture : pictures) {
            JsonNode reference = visualReferences.get(picture.pictureId());
            String expectedAnnotationId = reference.path("annotationSetId").asText("");

            if (!expectedAnnotationId.isBlank()
                    && (picture.annotation() == null
                            || !expectedAnnotationId.equals(
                                    picture.annotation().path("annotationSetId").asText()))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        ASSESSMENT_ANNOTATION_CHANGED,
                        "A referenced annotation set is no longer available; review the visual input"
                );
            }

            ObjectNode item = pictureSnapshots.addObject();
            item.put("pictureId", picture.pictureId().toString());
            item.put("sourceType", picture.sourceType());
            item.put("mediaType", picture.mediaType());
            item.put("width", picture.width());
            item.put("height", picture.height());
            item.set(
                    "viewState",
                    picture.viewState() == null
                            ? objectMapper.nullNode()
                            : picture.viewState().deepCopy());
            item.set(
                    "annotation",
                    picture.annotation() == null
                            ? objectMapper.nullNode()
                            : picture.annotation().deepCopy());
        }

        return new Snapshot(input.assessmentVersion(), snapshot);
    }

    @Override
    public List<AgentModelRequest.Picture> resolvePictures(
            UUID userId,
            JsonNode inputSnapshot
    ) {
        UUID assessmentId = UUID.fromString(inputSnapshot.path("assessmentId").asText());
        List<UUID> ids = pictureIds(inputSnapshot.path("pictures"));
        List<AgentModelRequest.Picture> resolved = new ArrayList<>();

        for (AgentImageClient.PictureResponse picture : resolveBatch(userId, assessmentId, ids)) {
            // 这里只刷新读取地址。标注始终使用冻结的 inputSnapshot，不以新标注覆盖旧快照。
            resolved.add(
                    new AgentModelRequest.Picture(
                            picture.pictureId(), picture.originalUrl(), picture.mediaType()));
        }

        return List.copyOf(resolved);
    }

    @Override
    public FinalizedResult finalizeResult(
            UUID userId,
            UUID sessionId,
            UUID outputStepId,
            UUID checkpointId,
            AgentOutcomeResponse outcome,
            String workflowVersion,
            int attemptNo,
            String generatedBy,
            String assessmentInputFingerprint
    ) {
        AgentHistoryClient.FinalizeRequest request =
                new AgentHistoryClient.FinalizeRequest(
                        sessionId,
                        outputStepId,
                        checkpointId,
                        attemptNo,
                        outcome.recommendationLevel().name(),
                        outcome.summary(),
                        outcome.reasons(),
                        outcome.suggestedNextActions(),
                        outcome.warningSigns(),
                        outcome.safetyNotice(),
                        outcome.finalizedAt(),
                        workflowVersion,
                        generatedBy,
                        assessmentInputFingerprint);

        try {
            AgentHistoryClient.FinalizedResponse result =
                    historyClient.finalizeResult(userId, outcome.assessmentId(), request);

            if (result == null
                    || result.resultId() == null
                    || result.revisionNo() < 1
                    || result.finalizedAt() == null) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        INVALID_FINALIZATION_RESPONSE,
                        "History did not acknowledge a finalized result"
                );
            }

            return new FinalizedResult(
                    result.resultId(), result.revisionNo(), result.finalizedAt());
        } catch (FeignException exception) {
            throw upstreamFailure(exception, RESULT_FINALIZATION_UNAVAILABLE);
        }
    }

    private List<AgentImageClient.PictureResponse> resolveBatch(
            UUID userId,
            UUID assessmentId,
            List<UUID> ids
    ) {
        if (ids.isEmpty()) {
            return List.of();
        }

        try {
            List<AgentImageClient.PictureResponse> response =
                    imageClient.resolve(
                            userId, new AgentImageClient.ResolveRequest(assessmentId, ids));
            if (response == null || response.size() != ids.size()) {
                throw invalidPictures();
            }

            Map<UUID, AgentImageClient.PictureResponse> byId = new HashMap<>();

            for (AgentImageClient.PictureResponse item : response) {
                if (item == null
                        || item.pictureId() == null
                        || item.originalUrl() == null
                        || item.mediaType() == null
                        || !item.mediaType().startsWith("image/")
                        || item.width() == null
                        || item.width() < 1
                        || item.height() == null
                        || item.height() < 1
                        || byId.put(item.pictureId(), item) != null) {
                    throw invalidPictures();
                }
            }

            if (!byId.keySet().equals(new HashSet<>(ids))) {
                throw invalidPictures();
            }

            return ids.stream().map(byId::get).toList();
        } catch (FeignException exception) {
            throw upstreamFailure(exception, ASSESSMENT_PICTURES_UNAVAILABLE);
        }
    }

    private List<UUID> pictureIds(JsonNode nodes) {
        if (!nodes.isArray() || nodes.size() > 12) {
            throw invalidPictures();
        }

        List<UUID> ids = new ArrayList<>();

        try {
            for (JsonNode node : nodes) {
                ids.add(UUID.fromString(node.path("pictureId").asText()));
            }
        } catch (IllegalArgumentException exception) {
            throw invalidPictures();
        }

        if (ids.stream().distinct().count() != ids.size()) {
            throw invalidPictures();
        }

        return List.copyOf(ids);
    }

    private ApiException invalidPictures() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                INVALID_PICTURE_SNAPSHOT,
                "Picture metadata is incomplete, duplicated or unavailable");
    }

    private ApiException upstreamFailure(
            FeignException exception,
            AIErrorCode code
    ) {
        HttpStatus status = switch (exception.status()) {
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        return new ApiException(
                status,
                code,
                "The upstream assessment/image operation could not be completed"
        );
    }
}
