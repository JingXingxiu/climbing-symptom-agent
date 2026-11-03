package org.javaup.history.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.validation.Validation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.javaup.history.api.dto.*;
import org.javaup.history.api.dto.HistoryEnums.*;
import org.javaup.history.api.problem.ApiException;
import org.javaup.history.entity.Assessment;
import org.javaup.history.entity.AssessmentResultRevision;
import org.javaup.history.mapper.AssessmentMapper;
import org.javaup.history.mapper.AssessmentResultMapper;
import org.javaup.history.mapper.AssessmentResultRevisionMapper;
import org.javaup.history.service.AssessmentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 单元测试覆盖业务决策与写入顺序；不声称验证了真实 PostgreSQL 行锁/回滚。 */
class AgentResultServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T00:00:00Z");
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private AssessmentMapper assessmentMapper;
    private AssessmentResultMapper resultMapper;
    private AssessmentResultRevisionMapper revisionMapper;
    private AssessmentService assessmentService;
    private AgentResultServiceImpl service;
    private Assessment assessment;

    @BeforeAll
    static void registerMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "agent-result-test"),
                Assessment.class);
    }

    @BeforeEach
    void setup() {
        assessmentMapper = mock(AssessmentMapper.class);
        resultMapper = mock(AssessmentResultMapper.class);
        revisionMapper = mock(AssessmentResultRevisionMapper.class);
        assessmentService = mock(AssessmentService.class);
        service = new AgentResultServiceImpl(assessmentMapper, resultMapper, revisionMapper,
                assessmentService, objectMapper, Validation.buildDefaultValidatorFactory().getValidator());
        assessment = new Assessment();
        assessment.setId(assessmentId);
        assessment.setUserId(userId);
        assessment.setStatus("COLLECTING");
        assessment.setVersion(8);
        assessment.setSubmittedAt(OffsetDateTime.parse("2026-10-27T00:00:00Z"));
        assessment.setLastSubmitVersion(7);
        when(assessmentMapper.selectOne(any())).thenReturn(assessment);
        when(assessmentService.get(userId, assessmentId)).thenReturn(aggregate(8, "climbing"));
        when(revisionMapper.insert(any(AssessmentResultRevision.class))).thenReturn(1);
        when(resultMapper.insert(any(org.javaup.history.entity.AssessmentResult.class))).thenReturn(1);
        when(assessmentMapper.updateById(any(Assessment.class))).thenReturn(1);
    }

    @Test
    void returnsSubmittedInputWithStableFingerprint() {
        var first = service.getAgentInput(userId, assessmentId);
        assertEquals(64, first.assessmentInputFingerprint().length());
        assertEquals(8, first.assessmentVersion());
        when(assessmentService.get(userId, assessmentId)).thenReturn(aggregate(20, "climbing"));
        assessment.setVersion(20);
        assertEquals(first.assessmentInputFingerprint(),
                service.getAgentInput(userId, assessmentId).assessmentInputFingerprint());
    }

    @Test
    void stableInputFingerprintChangesForChangedUserDescription() {
        var first = service.getAgentInput(userId, assessmentId);
        when(assessmentService.get(userId, assessmentId)).thenReturn(aggregate(8, "typing"));
        assertNotEquals(first.assessmentInputFingerprint(),
                service.getAgentInput(userId, assessmentId).assessmentInputFingerprint());
    }

    @Test
    void rejectsUnsubmittedInput() {
        assessment.setSubmittedAt(null);
        assertEquals("ASSESSMENT_NOT_SUBMITTED", assertThrows(ApiException.class,
                () -> service.getAgentInput(userId, assessmentId)).code());
        verifyNoInteractions(assessmentService);
    }

    @Test
    void otherUsersAssessmentIsNotFound() {
        when(assessmentMapper.selectOne(any())).thenReturn(null);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.getAgentInput(userId, assessmentId)).status().value());
    }

    @Test
    void finalizationWritesRevisionCurrentProjectionAndRoot() throws Exception {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        when(revisionMapper.maxRevisionNo(assessmentId)).thenReturn(2);
        var response = service.finalizeResult(userId, assessmentId, request(3, fingerprint));
        assertEquals(3, response.revisionNo());
        ArgumentCaptor<AssessmentResultRevision> revision = ArgumentCaptor.forClass(AssessmentResultRevision.class);
        verify(revisionMapper).insert(revision.capture());
        assertEquals(3, revision.getValue().getAgentAttemptNo());
        assertEquals("MOCK", revision.getValue().getGeneratedBy());
        assertEquals("COMPLETED", assessment.getStatus());
        assertEquals("RESULT", assessment.getCurrentStepKey());
        assertEquals(9, assessment.getVersion());
        var order = inOrder(revisionMapper, resultMapper, assessmentMapper);
        order.verify(revisionMapper).insert(any(AssessmentResultRevision.class));
        order.verify(resultMapper).selectById(assessmentId);
        order.verify(resultMapper).insert(any(org.javaup.history.entity.AssessmentResult.class));
        order.verify(assessmentMapper).updateById(assessment);
        assertNotNull(AgentResultServiceImpl.class.getMethod("finalizeResult", UUID.class, UUID.class,
                FinalizeAgentResultRequest.class).getAnnotation(Transactional.class));
    }

    @Test
    void deliveryRetryReplaysEvenAfterStatusAndInputChange() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        var request = request(1, fingerprint);
        service.finalizeResult(userId, assessmentId, request);
        ArgumentCaptor<AssessmentResultRevision> captor = ArgumentCaptor.forClass(AssessmentResultRevision.class);
        verify(revisionMapper).insert(captor.capture());
        when(revisionMapper.findBySession(request.sessionId())).thenReturn(captor.getValue());
        assessment.setStatus("CANCELLED");
        clearInvocations(assessmentService, revisionMapper, resultMapper);
        var replay = service.finalizeResult(userId, assessmentId, request);
        assertEquals(captor.getValue().getId(), replay.resultId());
        verifyNoInteractions(assessmentService, resultMapper);
        verify(revisionMapper, never()).insert(any(AssessmentResultRevision.class));
        verify(revisionMapper, never()).maxAttemptNo(any());
    }

    @Test
    void replayRejectsSameSessionWithDifferentContent() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        var request = request(1, fingerprint);
        service.finalizeResult(userId, assessmentId, request);
        ArgumentCaptor<AssessmentResultRevision> captor = ArgumentCaptor.forClass(AssessmentResultRevision.class);
        verify(revisionMapper).insert(captor.capture());
        captor.getValue().setSummary("different");
        when(revisionMapper.findBySession(request.sessionId())).thenReturn(captor.getValue());
        assertEquals("AGENT_RESULT_IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> service.finalizeResult(userId, assessmentId, request)).code());
    }

    @Test
    void lateOlderAttemptCannotReplaceNewerCurrentResult() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        when(revisionMapper.maxAttemptNo(assessmentId)).thenReturn(3);
        assertEquals("AGENT_RESULT_STALE_ATTEMPT", assertThrows(ApiException.class,
                () -> service.finalizeResult(userId, assessmentId, request(2, fingerprint))).code());
        verify(revisionMapper, never()).insert(any(AssessmentResultRevision.class));
        verifyNoInteractions(resultMapper);
    }

    @Test
    void editedInputCannotReceiveResultFromOldSnapshot() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        when(assessmentService.get(userId, assessmentId)).thenReturn(aggregate(10, "changed description"));
        assertEquals("ASSESSMENT_INPUT_CHANGED", assertThrows(ApiException.class,
                () -> service.finalizeResult(userId, assessmentId, request(1, fingerprint))).code());
        verify(revisionMapper, never()).insert(any(AssessmentResultRevision.class));
        verifyNoInteractions(resultMapper);
    }

    @Test
    void failedRevisionInsertDoesNotContinueUpdatingProjection() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        when(revisionMapper.insert(any(AssessmentResultRevision.class))).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> service.finalizeResult(userId, assessmentId, request(1, fingerprint)));
        verifyNoInteractions(resultMapper);
        verify(assessmentMapper, never()).updateById(any(Assessment.class));
    }

    @Test
    void freezesReferencesInRevisionAndCurrentResult() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        ResultReference reference = new ResultReference("手部结构参考", null, "PIP 位置说明",
                "KNOWLEDGE", UUID.randomUUID(), UUID.randomUUID(), "reviewed-2026-11");
        FinalizeAgentResultRequest request = withReferences(request(1, fingerprint), List.of(reference));
        service.finalizeResult(userId, assessmentId, request);
        ArgumentCaptor<AssessmentResultRevision> revision = ArgumentCaptor.forClass(AssessmentResultRevision.class);
        ArgumentCaptor<org.javaup.history.entity.AssessmentResult> current =
                ArgumentCaptor.forClass(org.javaup.history.entity.AssessmentResult.class);
        verify(revisionMapper).insert(revision.capture());
        verify(resultMapper).insert(current.capture());
        assertEquals(objectMapper.valueToTree(List.of(reference)),
                objectMapper.readTree(revision.getValue().getSourceReferences().getValue()));
        assertEquals(revision.getValue().getSourceReferences(), current.getValue().getSourceReferences());
        assertFalse(current.getValue().getSourceReferences().getValue().contains("provenanceValid"));
        AssessmentResultResponse response = AssessmentResultResponse.fromEntity(current.getValue(), objectMapper);
        assertEquals(List.of(reference), response.references());
    }

    @Test
    void sameSessionCannotReplayDifferentSources() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        var original = withReferences(request(1, fingerprint), List.of(new ResultReference(
                "公开参考", "https://example.org/hand", "节选", "WEB_SEARCH", null, null, null)));
        service.finalizeResult(userId, assessmentId, original);
        ArgumentCaptor<AssessmentResultRevision> captor = ArgumentCaptor.forClass(AssessmentResultRevision.class);
        verify(revisionMapper).insert(captor.capture());
        when(revisionMapper.findBySession(original.sessionId())).thenReturn(captor.getValue());
        assertEquals(captor.getValue().getId(), service.finalizeResult(userId, assessmentId, original).resultId());
        assertEquals("AGENT_RESULT_IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> service.finalizeResult(userId, assessmentId, withReferences(original, List.of()))).code());
    }

    @Test
    void rejectsKnowledgeSourceWithoutStableIdsAndOversizedSources() {
        String fingerprint = service.getAgentInput(userId, assessmentId).assessmentInputFingerprint();
        ResultReference invalid = new ResultReference("缺失来源", null, "节选", "KNOWLEDGE", null, null, null);
        assertEquals("INVALID_AGENT_RESULT", assertThrows(ApiException.class,
                () -> service.finalizeResult(userId, assessmentId,
                        withReferences(request(1, fingerprint), List.of(invalid)))).code());
        ResultReference valid = new ResultReference("公开资料", "https://example.org/hand", "节选",
                "WEB_SEARCH", null, null, null);
        assertEquals("INVALID_AGENT_RESULT", assertThrows(ApiException.class,
                () -> service.finalizeResult(userId, assessmentId,
                        withReferences(request(1, fingerprint), java.util.Collections.nCopies(21, valid)))).code());
    }

    private FinalizeAgentResultRequest withReferences(
            FinalizeAgentResultRequest original, List<ResultReference> references
    ) {
        return new FinalizeAgentResultRequest(original.sessionId(), original.stepId(), original.checkpointId(),
                original.attemptNo(), original.recommendationLevel(), original.summary(), original.reasons(),
                original.suggestedNextActions(), original.warningSigns(), original.safetyNotice(),
                original.finalizedAt(), original.workflowVersion(), original.generatedBy(),
                original.assessmentInputFingerprint(), references);
    }

    private FinalizeAgentResultRequest request(int attempt, String fingerprint) {
        return new FinalizeAgentResultRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), attempt,
                RecommendationLevel.ROUTINE_PROFESSIONAL, "演示结果，仅供测试", List.of("演示原因"),
                List.of("演示行动"), List.of(), "不是医学诊断", now, "learning-v1", ResultGeneratedBy.MOCK, fingerprint);
    }

    private AssessmentResponse aggregate(int version, String activity) {
        return new AssessmentResponse(assessmentId, userId, AssessmentStatus.COLLECTING,
                new InitialSymptomDetails(HandSide.RIGHT, List.of(SymptomType.PAIN), OnsetKind.TODAY,
                        null, null, null, activity, NormalUseImpact.MILD, null),
                List.of(new SymptomLocationResponse(locationId, HandSide.RIGHT, "hand.right.middle.pip",
                        "右手中指PIP附近", FingerCode.MIDDLE, JointCode.PIP, null, now)),
                List.of(), List.of(), CurrentStepKey.AGENT, version, now, now, null);
    }
}
