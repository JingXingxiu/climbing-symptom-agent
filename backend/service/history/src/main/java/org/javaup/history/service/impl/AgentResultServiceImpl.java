package org.javaup.history.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.javaup.history.api.dto.AgentAssessmentInputResponse;
import org.javaup.history.api.dto.FinalizeAgentResultRequest;
import org.javaup.history.api.dto.FinalizedAgentResultResponse;
import org.javaup.history.api.dto.HistoryEnums;
import org.javaup.history.api.problem.ApiException;
import org.javaup.history.entity.Assessment;
import org.javaup.history.entity.AssessmentResult;
import org.javaup.history.entity.AssessmentResultRevision;
import org.javaup.history.mapper.AssessmentMapper;
import org.javaup.history.mapper.AssessmentResultMapper;
import org.javaup.history.mapper.AssessmentResultRevisionMapper;
import org.javaup.history.service.AgentResultService;
import org.javaup.history.service.AssessmentService;
import org.postgresql.util.PGobject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class AgentResultServiceImpl implements AgentResultService {

    private final AssessmentMapper assessmentMapper;
    private final AssessmentResultMapper resultMapper;
    private final AssessmentResultRevisionMapper revisionMapper;
    private final AssessmentService assessmentService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentAssessmentInputResponse getAgentInput(UUID userId, UUID assessmentId) {
        Assessment assessment = requireOwned(userId, assessmentId, false);
        requireSubmitted(assessment);
        JsonNode aggregate = objectMapper.valueToTree(assessmentService.get(userId, assessmentId));
        return new AgentAssessmentInputResponse(assessmentId, userId, assessment.getVersion(),
                assessment.getSubmittedAt().toInstant(), fingerprint(aggregate), aggregate);
    }

    @Override
    @Transactional
    public FinalizedAgentResultResponse finalizeResult(UUID userId, UUID assessmentId,
            FinalizeAgentResultRequest request) {
        if (request == null || !validator.validate(request).isEmpty()
                || request.generatedBy() == HistoryEnums.ResultGeneratedBy.RULE) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_AGENT_RESULT", "A complete AI or MOCK result is required");
        }
        Assessment assessment = requireOwned(userId, assessmentId, true);

        // 同一个 Session 的重试先回放，不被后来新增的版本、状态或输入变更误伤。
        AssessmentResultRevision existing = revisionMapper.findBySession(request.sessionId());
        if (existing != null) {
            if (!assessmentId.equals(existing.getAssessmentId()) || !sameResult(existing, request)) {
                throw error(HttpStatus.CONFLICT, "AGENT_RESULT_IDEMPOTENCY_CONFLICT",
                        "This session already finalized a different result");
            }
            return response(existing);
        }
        requireSubmitted(assessment);
        if (request.attemptNo() <= revisionMapper.maxAttemptNo(assessmentId)) {
            throw error(HttpStatus.CONFLICT, "AGENT_RESULT_STALE_ATTEMPT",
                    "A newer analysis attempt has already finalized a result");
        }
        JsonNode currentInput = objectMapper.valueToTree(assessmentService.get(userId, assessmentId));
        if (!request.assessmentInputFingerprint().equals(fingerprint(currentInput))) {
            throw error(HttpStatus.CONFLICT, "ASSESSMENT_INPUT_CHANGED",
                    "The assessment input changed after this analysis started; create a new analysis");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AssessmentResultRevision revision = toRevision(assessmentId, request,
                revisionMapper.maxRevisionNo(assessmentId) + 1, now);
        if (revisionMapper.insert(revision) != 1) throw new IllegalStateException("Result revision was not persisted");

        // 历史版本和当前投影在同一 History 事务写入；不会调用远端服务。
        AssessmentResult current = toCurrentResult(revision, now);
        int affected = resultMapper.selectById(assessmentId) == null
                ? resultMapper.insert(current) : resultMapper.updateById(current);
        if (affected != 1) throw new IllegalStateException("Current result was not persisted");
        assessment.setStatus(HistoryEnums.AssessmentStatus.COMPLETED.name());
        assessment.setCurrentStepKey(HistoryEnums.CurrentStepKey.RESULT.name());
        assessment.setCompletedAt(now);
        assessment.setUpdatedAt(now);
        assessment.setLastSavedAt(now);
        assessment.setVersion(assessment.getVersion() + 1);
        if (assessmentMapper.updateById(assessment) != 1) {
            throw new IllegalStateException("Assessment completion was not persisted");
        }
        return response(revision);
    }

    private Assessment requireOwned(UUID userId, UUID assessmentId, boolean forUpdate) {
        if (userId == null || assessmentId == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ASSESSMENT_REFERENCE", "User and assessment are required");
        }
        var query = Wrappers.<Assessment>lambdaQuery()
                .eq(Assessment::getId, assessmentId).eq(Assessment::getUserId, userId);
        if (forUpdate) query.last("FOR UPDATE");
        Assessment result = assessmentMapper.selectOne(query);
        if (result == null) throw error(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment does not exist");
        return result;
    }

    private void requireSubmitted(Assessment assessment) {
        if (assessment.getSubmittedAt() == null || assessment.getLastSubmitVersion() == null
                || HistoryEnums.AssessmentStatus.DRAFT.name().equals(assessment.getStatus())
                || HistoryEnums.AssessmentStatus.CANCELLED.name().equals(assessment.getStatus())) {
            throw error(HttpStatus.CONFLICT, "ASSESSMENT_NOT_SUBMITTED", "A submitted active assessment is required");
        }
    }

    /** 只摘要稳定业务输入，不把答案追加、version、页面状态或时间混入判断。 */
    private String fingerprint(JsonNode aggregate) {
        ObjectNode stable = objectMapper.createObjectNode();
        stable.set("initialInput", aggregate.path("initialInput").deepCopy());
        var locations = stable.putArray("symptomLocations");
        List<JsonNode> sortedLocations = new ArrayList<>();
        for (JsonNode location : aggregate.path("symptomLocations")) {
            ObjectNode copy = (ObjectNode) location.deepCopy();
            copy.remove("selectedAt");
            sortedLocations.add(copy);
        }
        sortedLocations.sort(Comparator.comparing(node -> node.path("locationId").asText()));
        sortedLocations.forEach(locations::add);
        stable.set("visualReferences", aggregate.path("visualReferences").deepCopy());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writeValueAsBytes(canonical(stable))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            value.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, child) -> object.set(key, canonical(child)));
            return object;
        }
        if (value.isArray()) {
            var array = objectMapper.createArrayNode();
            for (JsonNode child : value) array.add(canonical(child));
            return array;
        }
        return value;
    }

    private AssessmentResultRevision toRevision(UUID assessmentId, FinalizeAgentResultRequest request,
            int revisionNo, OffsetDateTime now) {
        AssessmentResultRevision revision = new AssessmentResultRevision();
        revision.setId(UUID.randomUUID());
        revision.setAssessmentId(assessmentId);
        revision.setRevisionNo(revisionNo);
        revision.setAgentSessionId(request.sessionId());
        revision.setAgentAttemptNo(request.attemptNo());
        revision.setAgentStepId(request.stepId());
        revision.setAgentCheckpointId(request.checkpointId());
        revision.setAssessmentInputFingerprint(request.assessmentInputFingerprint());
        revision.setRecommendationLevel(request.recommendationLevel().name());
        revision.setSummary(request.summary());
        revision.setReasons(json(request.reasons()));
        revision.setSuggestedNextActions(json(request.suggestedNextActions()));
        revision.setWarningSigns(json(request.warningSigns()));
        revision.setGeneratedBy(request.generatedBy().name());
        revision.setGeneratorReference(request.workflowVersion());
        revision.setSafetyNotice(request.safetyNotice());
        // PostgreSQL timestamptz 保存微秒，先统一精度，避免重试比较因纳秒丢失而冲突。
        revision.setFinalizedAt(OffsetDateTime.ofInstant(request.finalizedAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
        revision.setCreatedAt(now);
        return revision;
    }

    private AssessmentResult toCurrentResult(AssessmentResultRevision revision, OffsetDateTime now) {
        AssessmentResult result = new AssessmentResult();
        result.setAssessmentId(revision.getAssessmentId());
        result.setRecommendationLevel(revision.getRecommendationLevel());
        result.setSummary(revision.getSummary());
        result.setReasons(revision.getReasons());
        result.setSuggestedNextActions(revision.getSuggestedNextActions());
        result.setWarningSigns(revision.getWarningSigns());
        result.setGeneratedBy(revision.getGeneratedBy());
        result.setGeneratorReference(revision.getGeneratorReference());
        result.setSafetyNotice(revision.getSafetyNotice());
        result.setFinalizedAt(revision.getFinalizedAt());
        result.setUpdatedAt(now);
        return result;
    }

    private boolean sameResult(AssessmentResultRevision existing, FinalizeAgentResultRequest request) {
        return Objects.equals(existing.getAgentStepId(), request.stepId())
                && Objects.equals(existing.getAssessmentInputFingerprint(), request.assessmentInputFingerprint())
                && Objects.equals(existing.getAgentCheckpointId(), request.checkpointId())
                && Objects.equals(existing.getAgentAttemptNo(), request.attemptNo())
                && Objects.equals(existing.getRecommendationLevel(), request.recommendationLevel().name())
                && Objects.equals(existing.getSummary(), request.summary())
                && Objects.equals(existing.getGeneratedBy(), request.generatedBy().name())
                && Objects.equals(existing.getGeneratorReference(), request.workflowVersion())
                && Objects.equals(existing.getSafetyNotice(), request.safetyNotice())
                && Objects.equals(existing.getFinalizedAt().toInstant(), request.finalizedAt().truncatedTo(ChronoUnit.MICROS))
                && sameJson(existing.getReasons(), request.reasons())
                && sameJson(existing.getSuggestedNextActions(), request.suggestedNextActions())
                && sameJson(existing.getWarningSigns(), request.warningSigns());
    }

    private boolean sameJson(PGobject existing, Object expected) {
        return existing != null && objectMapper.readTree(existing.getValue()).equals(objectMapper.valueToTree(expected));
    }

    private PGobject json(Object value) {
        PGobject json = new PGobject();
        json.setType("jsonb");
        try {
            json.setValue(objectMapper.writeValueAsString(value));
            return json;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not prepare result JSONB", exception);
        }
    }

    private FinalizedAgentResultResponse response(AssessmentResultRevision revision) {
        return new FinalizedAgentResultResponse(revision.getId(), revision.getRevisionNo(),
                revision.getFinalizedAt().toInstant());
    }

    private ApiException error(HttpStatus status, String code, String detail) {
        return new ApiException(status, code, detail);
    }
}
