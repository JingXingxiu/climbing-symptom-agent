package org.javaup.ai.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.javaup.ai.api.dto.*;
import org.javaup.ai.api.dto.AgentEnums.*;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.client.AgentPaymentClient;
import org.javaup.ai.client.AgentPaymentClient.EntitlementResponse;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.javaup.ai.client.PaymentEntitlementGuard;
import org.javaup.ai.config.AgentWorkflowProperties;
import org.javaup.ai.config.PaymentEntitlementBreakerConfiguration;
import org.javaup.ai.config.PaymentEntitlementBreakerProperties;
import org.javaup.ai.entity.*;
import org.javaup.ai.mapper.*;
import org.javaup.ai.model.*;
import org.javaup.ai.observability.AgentTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.javaup.ai.service.AgentAssessmentBridge;
import org.javaup.ai.service.AgentJson;
import org.javaup.ai.service.AgentQuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 真实Service＋模拟Mapper的状态机测试。查询返回独立Entity，避免共享引用掩盖过期响应问题。
 * 不声称验证真实PostgreSQL的锁、唯一约束或事务回滚，数据库集成需另行执行迁移后验证。
 */
class AgentServiceImplTest {
    private static final Instant TIME = Instant.parse("2026-10-27T02:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final AgentJson json = new AgentJson(mapper);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AgentTelemetry telemetry = new AgentTelemetry(ObservationRegistry.create(), metrics);
    private final AgentSessionMapper sessions = mock(AgentSessionMapper.class);
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final AgentCheckpointMapper checkpoints = mock(AgentCheckpointMapper.class);
    private final ModelCallMapper calls = mock(ModelCallMapper.class);
    private final AgentAssessmentBridge bridge = mock(AgentAssessmentBridge.class);
    private final AgentModelClient model = mock(AgentModelClient.class);
    private final AgentPaymentClient payment = mock(AgentPaymentClient.class);
    private final UserDailyUsageMapper usage = mock(UserDailyUsageMapper.class);
    private final Map<LocalDate, Integer> usageRows = new LinkedHashMap<>();
    private final AgentWorkflowProperties settings = new AgentWorkflowProperties();
    private final Map<UUID, AgentSession> sessionRows = new LinkedHashMap<>();
    private final Map<UUID, AgentStep> stepRows = new LinkedHashMap<>();
    private final Map<UUID, AgentCheckpoint> checkpointRows = new LinkedHashMap<>();
    private final Map<UUID, ModelCall> callRows = new LinkedHashMap<>();
    private AgentServiceImpl service;
    private boolean inTransaction;

    @BeforeEach
    void setUp() {
        TransactionTemplate transactions =
                new TransactionTemplate() {
                    @Override
                    public <T> T execute(TransactionCallback<T> action) {
                        boolean previous = inTransaction;
                        inTransaction = true;
                        try {
                            return action.doInTransaction(new SimpleTransactionStatus());
                        } finally {
                            inTransaction = previous;
                        }
                    }
                };
        service =
                new AgentServiceImpl(
                        sessions,
                        steps,
                        checkpoints,
                        calls,
                        transactions,
                        bridge,
                        model,
                        new AgentQuotaService(
                                new PaymentEntitlementBreakerConfiguration()
                                        .paymentEntitlementGuard(payment, new PaymentEntitlementBreakerProperties()),
                                usage,
                                Clock.fixed(TIME, ZoneOffset.UTC)),
                        settings,
                        json,
                        Clock.fixed(TIME, ZoneOffset.UTC),
                        telemetry);
        when(payment.getEntitlement(userId)).thenAnswer(invocation -> {
            assertFalse(inTransaction, "Payment must be queried outside the execution transaction");
            return new EntitlementResponse(userId, "FREE", "免费", 10, null);
        });
        when(usage.reserve(eq(userId), any(), anyInt(), any())).thenAnswer(invocation -> {
            assertTrue(inTransaction, "Usage must share the session reservation transaction");
            LocalDate date = invocation.getArgument(1);
            int used = usageRows.getOrDefault(date, 0);
            if (used >= (int) invocation.getArgument(2)) {
                return 0;
            }
            usageRows.put(date, used + 1);
            return 1;
        });
        when(sessions.selectByRequest(any(), any()))
                .thenAnswer(
                        invocation ->
                                sessionRows.values().stream()
                                        .filter(
                                                row ->
                                                        row.getUserId()
                                                                        .equals(
                                                                                invocation
                                                                                        .getArgument(
                                                                                                0))
                                                                && row.getClientRequestId()
                                                                        .equals(
                                                                                invocation
                                                                                        .getArgument(
                                                                                                1)))
                                        .findFirst()
                                        .map(this::copy)
                                        .orElse(null));
        when(sessions.selectById(any()))
                .thenAnswer(invocation -> copy(sessionRows.get(invocation.getArgument(0))));
        when(sessions.selectOwnedForUpdate(any(), any()))
                .thenAnswer(
                        invocation -> {
                            AgentSession row = sessionRows.get(invocation.getArgument(1));
                            return row != null && row.getUserId().equals(invocation.getArgument(0))
                                    ? copy(row)
                                    : null;
                        });
        when(sessions.nextAttempt(any()))
                .thenAnswer(
                        invocation ->
                                sessionRows.values().stream()
                                                .filter(
                                                        row ->
                                                                row.getAssessmentId()
                                                                        .equals(
                                                                                invocation
                                                                                        .getArgument(
                                                                                                0)))
                                                .mapToInt(AgentSession::getAttemptNo)
                                                .max()
                                                .orElse(0)
                                        + 1);
        when(sessions.insertIfAbsent(any(AgentSession.class)))
                .thenAnswer(
                        invocation -> {
                            AgentSession row = invocation.getArgument(0);
                            boolean duplicate =
                                    sessionRows.values().stream()
                                            .anyMatch(
                                                    old ->
                                                            old.getUserId().equals(row.getUserId())
                                                                            && old.getClientRequestId()
                                                                                    .equals(
                                                                                            row
                                                                                                    .getClientRequestId())
                                                                    || old.getAssessmentId()
                                                                                    .equals(
                                                                                            row
                                                                                                    .getAssessmentId())
                                                                            && !List.of(
                                                                                            "COMPLETED",
                                                                                            "CANCELLED")
                                                                                    .contains(
                                                                                            old
                                                                                                    .getStatus()));
                            if (duplicate) return 0;
                            sessionRows.put(row.getId(), copy(row));
                            return 1;
                        });
        when(sessions.updateState(any()))
                .thenAnswer(
                        invocation -> {
                            AgentSession row = invocation.getArgument(0);
                            sessionRows.put(row.getId(), copy(row));
                            return 1;
                        });
        when(steps.insert(any(AgentStep.class)))
                .thenAnswer(
                        invocation -> {
                            AgentStep row = invocation.getArgument(0);
                            stepRows.put(row.getId(), copy(row));
                            return 1;
                        });
        when(steps.selectById(any()))
                .thenAnswer(invocation -> copy(stepRows.get(invocation.getArgument(0))));
        when(steps.selectSessionSteps(any()))
                .thenAnswer(
                        invocation ->
                                stepRows.values().stream()
                                        .filter(
                                                row ->
                                                        row.getSessionId()
                                                                .equals(invocation.getArgument(0)))
                                        .sorted(Comparator.comparingInt(AgentStep::getStepNo))
                                        .map(this::copy)
                                        .toList());
        when(steps.selectByOperation(any(), any()))
                .thenAnswer(
                        invocation ->
                                stepRows.values().stream()
                                        .filter(
                                                row ->
                                                        row.getSessionId()
                                                                        .equals(
                                                                                invocation
                                                                                        .getArgument(
                                                                                                0))
                                                                && Objects.equals(
                                                                        row.getClientOperationId(),
                                                                        invocation.getArgument(1)))
                                        .findFirst()
                                        .map(this::copy)
                                        .orElse(null));
        when(checkpoints.insert(any(AgentCheckpoint.class)))
                .thenAnswer(
                        invocation -> {
                            AgentCheckpoint row = invocation.getArgument(0);
                            checkpointRows.put(row.getId(), copy(row));
                            return 1;
                        });
        when(checkpoints.selectById(any()))
                .thenAnswer(invocation -> copy(checkpointRows.get(invocation.getArgument(0))));
        when(checkpoints.nextNumber(any()))
                .thenAnswer(
                        invocation ->
                                checkpointRows.values().stream()
                                                .filter(
                                                        row ->
                                                                row.getSessionId()
                                                                        .equals(
                                                                                invocation
                                                                                        .getArgument(
                                                                                                0)))
                                                .mapToInt(AgentCheckpoint::getCheckpointNo)
                                                .max()
                                                .orElse(0)
                                        + 1);
        when(checkpoints.selectInitial(any()))
                .thenAnswer(
                        invocation ->
                                checkpointRows.values().stream()
                                        .filter(
                                                row ->
                                                        row.getSessionId()
                                                                        .equals(
                                                                                invocation
                                                                                        .getArgument(
                                                                                                0))
                                                                && row.getStepId() == null)
                                        .findFirst()
                                        .map(this::copy)
                                        .orElse(null));
        when(checkpoints.selectByStep(any(), any()))
                .thenAnswer(
                        invocation ->
                                checkpointRows.values().stream()
                                        .filter(
                                                row ->
                                                        row.getSessionId()
                                                                        .equals(
                                                                                invocation
                                                                                        .getArgument(
                                                                                                0))
                                                                && Objects.equals(
                                                                        row.getStepId(),
                                                                        invocation.getArgument(1)))
                                        .findFirst()
                                        .map(this::copy)
                                        .orElse(null));
        when(calls.insert(any(ModelCall.class)))
                .thenAnswer(
                        invocation -> {
                            ModelCall row = invocation.getArgument(0);
                            callRows.put(row.getId(), copy(row));
                            return 1;
                        });
        when(calls.updateById(any(ModelCall.class)))
                .thenAnswer(
                        invocation -> {
                            ModelCall row = invocation.getArgument(0);
                            callRows.put(row.getId(), copy(row));
                            return 1;
                        });
        when(calls.discardInflight(any(), anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            int changed = 0;
                            for (ModelCall row : callRows.values()) {
                                if (row.getSessionId().equals(invocation.getArgument(0))
                                        && row.getExecutionGeneration()
                                                == (long) invocation.getArgument(1)
                                        && "STARTED".equals(row.getStatus())) {
                                    row.setStatus("DISCARDED");
                                    row.setCompletedAt(invocation.getArgument(2));
                                    changed++;
                                }
                            }
                            return changed;
                        });
        ObjectNode snapshot = json.object();
        snapshot.put("assessmentInputFingerprint", "a".repeat(64));
        when(bridge.loadSnapshot(userId, assessmentId))
                .thenReturn(new AgentAssessmentBridge.Snapshot(5, snapshot));
        when(bridge.resolvePictures(eq(userId), any())).thenReturn(List.of());
        FakeAgentModel fake = new FakeAgentModel(mapper);
        when(model.analyze(any()))
                .thenAnswer(invocation -> fake.analyze(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanUpBreaker() {
        DegradeRuleManager.setRulesForResource(PaymentEntitlementGuard.RESOURCE, Set.of());
    }

    @Test
    void createFreezesInitialStateAndReplayDoesNotReloadExternalInput() {
        UUID key = UUID.randomUUID();
        AgentSessionResponse first = service.createAgentSession(userId, key, request());
        AgentSessionResponse second = service.createAgentSession(userId, key, request());
        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(first.activeCheckpointId(), second.activeCheckpointId());
        assertEquals(AgentSessionStatus.CREATED, first.status());
        assertEquals("zhipu", first.modelProvider());
        assertEquals("glm-4.6v-flash", first.modelName());
        assertEquals(1, sessionRows.size());
        assertEquals(1, checkpointRows.size());
        assertEquals(0, stepRows.size());
        verify(bridge, times(1)).loadSnapshot(userId, assessmentId);
        verify(model, never()).analyze(any());
        verifyNoInteractions(payment, usage);
    }

    @Test
    void creationKeyCannotBeReusedForDifferentPayload() {
        UUID key = UUID.randomUUID();
        service.createAgentSession(userId, key, request());
        CreateAgentSessionRequest changed =
                new CreateAgentSessionRequest(assessmentId, "en-US", null, null, null, null);
        assertCode(
                "IDEMPOTENCY_KEY_REUSED", () -> service.createAgentSession(userId, key, changed));
        assertEquals(1, sessionRows.size());
    }

    @Test
    void readChecksOwnershipAndNeverStartsTheModel() {
        AgentSessionResponse created = create();
        clearInvocations(model, bridge, payment, usage);
        assertCode(
                "AGENT_SESSION_NOT_FOUND",
                () -> service.getAgentSession(UUID.randomUUID(), created.sessionId()));
        AgentSessionResponse read = service.getAgentSession(userId, created.sessionId());
        assertEquals(created.version(), read.version());
        verifyNoInteractions(model, bridge, payment, usage);
    }

    @Test
    void executionPersistsTraceIdForLaterObservationLookup() {
        advance(create());

        ModelCall call = callRows.values().iterator().next();
        assertNotNull(call.getTraceId());
        assertTrue(call.getTraceId().matches("[0-9a-f]{32}"));
        assertEquals("SUCCEEDED", call.getStatus());
    }

    @Test
    void activeLookupRecoversSessionWithoutClientCachedIdAndDoesNotResumeIt() {
        AgentSessionResponse created = create();
        AgentSessionResponse paused =
                service.pauseSession(userId, created.sessionId(), version(created));
        when(sessions.selectOpen(userId, assessmentId))
                .thenReturn(copy(sessionRows.get(created.sessionId())));
        clearInvocations(model, bridge, payment, usage);
        AgentSessionResponse recovered = service.getActiveSession(userId, assessmentId);
        assertEquals(paused.sessionId(), recovered.sessionId());
        assertEquals(AgentSessionStatus.PAUSED, recovered.status());
        assertEquals(paused.version(), recovered.version());
        assertCode(
                "ACTIVE_SESSION_NOT_FOUND",
                () -> service.getActiveSession(UUID.randomUUID(), assessmentId));
        verifyNoInteractions(model, bridge, payment, usage);
    }

    @Test
    void answerChecksVersionQuestionAndCheckpointBeforeAppending() {
        AgentSessionResponse waiting = advance(create());
        assertCode(
                "SESSION_VERSION_CONFLICT",
                () ->
                        service.answerAgentQuestion(
                                userId,
                                waiting.sessionId(),
                                UUID.randomUUID(),
                                new AnswerAgentQuestionRequest(
                                        waiting.question().questionId(),
                                        json.node("握持时"),
                                        null,
                                        waiting.version() - 1,
                                        waiting.activeCheckpointId())));
        assertCode(
                "CHECKPOINT_CHANGED",
                () ->
                        service.answerAgentQuestion(
                                userId,
                                waiting.sessionId(),
                                UUID.randomUUID(),
                                new AnswerAgentQuestionRequest(
                                        waiting.question().questionId(),
                                        json.node("握持时"),
                                        null,
                                        waiting.version(),
                                        UUID.randomUUID())));
        assertCode(
                "QUESTION_CHANGED",
                () ->
                        service.answerAgentQuestion(
                                userId,
                                waiting.sessionId(),
                                UUID.randomUUID(),
                                new AnswerAgentQuestionRequest(
                                        UUID.randomUUID(),
                                        json.node("握持时"),
                                        null,
                                        waiting.version(),
                                        waiting.activeCheckpointId())));
        assertEquals(1, stepRows.size());
        AgentSessionResponse answered = answer(waiting, "握持时");
        assertEquals(AgentSessionStatus.CREATED, answered.status());
        assertNull(answered.question());
        assertEquals(2, stepRows.size());
    }

    @Test
    void answerReplayAfterPauseDoesNotRewindOrDuplicateItsBranch() {
        AgentSessionResponse waiting = advance(create());
        UUID key = UUID.randomUUID();
        AnswerAgentQuestionRequest request =
                new AnswerAgentQuestionRequest(
                        waiting.question().questionId(),
                        json.node("握持时"),
                        null,
                        waiting.version(),
                        waiting.activeCheckpointId());
        AgentSessionResponse saved =
                service.answerAgentQuestion(userId, waiting.sessionId(), key, request);
        AgentSessionResponse paused =
                service.pauseSession(userId, saved.sessionId(), version(saved));
        AgentSessionResponse replay =
                service.answerAgentQuestion(userId, waiting.sessionId(), key, request);
        assertEquals(AgentSessionStatus.PAUSED, replay.status());
        assertEquals(paused.activeCheckpointId(), replay.activeCheckpointId());
        assertEquals(2, stepRows.size());
        assertCode(
                "IDEMPOTENCY_KEY_REUSED",
                () ->
                        service.answerAgentQuestion(
                                userId,
                                waiting.sessionId(),
                                key,
                                new AnswerAgentQuestionRequest(
                                        waiting.question().questionId(),
                                        json.node("其他内容"),
                                        null,
                                        waiting.version(),
                                        waiting.activeCheckpointId())));
    }

    @Test
    void multiChoiceRejectsUnknownAndDuplicateValues() {
        AgentQuestion question =
                new AgentQuestion(
                        UUID.randomUUID(),
                        "symptom.kinds",
                        "请选择表现",
                        null,
                        AgentAnswerType.MULTI_CHOICE,
                        List.of(
                                new AgentQuestionOption("aching", "酸"),
                                new AgentQuestionOption("numbness", "麻")),
                        true);
        doReturn(questionResult(question)).when(model).analyze(any());
        AgentSessionResponse waiting = advance(create());
        for (JsonNode invalid :
                List.of(
                        json.node(List.of("unknown")),
                        json.node(List.of("aching", "aching")),
                        json.node("aching"))) {
            assertCode(
                    "ANSWER_TYPE_MISMATCH",
                    () ->
                            service.answerAgentQuestion(
                                    userId,
                                    waiting.sessionId(),
                                    UUID.randomUUID(),
                                    new AnswerAgentQuestionRequest(
                                            question.questionId(),
                                            invalid,
                                            null,
                                            waiting.version(),
                                            waiting.activeCheckpointId())));
        }
        AgentSessionResponse accepted =
                service.answerAgentQuestion(
                        userId,
                        waiting.sessionId(),
                        UUID.randomUUID(),
                        new AnswerAgentQuestionRequest(
                                question.questionId(),
                                json.node(List.of("aching", "numbness")),
                                null,
                                waiting.version(),
                                waiting.activeCheckpointId()));
        assertEquals(AgentSessionStatus.CREATED, accepted.status());
    }

    @Test
    void pausedQuestionResumesAtSameCheckpointWithoutCallingModel() {
        AgentSessionResponse waiting = advance(create());
        AgentSessionResponse paused =
                service.pauseSession(userId, waiting.sessionId(), version(waiting));
        long generation = sessionRows.get(waiting.sessionId()).getExecutionGeneration();
        clearInvocations(model, payment, usage);
        AgentSessionResponse resumed =
                service.resumeSession(userId, waiting.sessionId(), version(paused));
        assertEquals(AgentSessionStatus.WAITING_FOR_USER, resumed.status());
        assertEquals(waiting.activeCheckpointId(), resumed.activeCheckpointId());
        assertEquals(waiting.question().questionId(), resumed.question().questionId());
        assertTrue(sessionRows.get(waiting.sessionId()).getExecutionGeneration() > generation);
        verifyNoInteractions(model, payment, usage);
    }

    @Test
    void revisionBranchesBeforeOriginalAnswerAndNeverMixesOldDescendants() {
        AgentSessionResponse firstQuestion = advance(create());
        AgentSessionResponse firstAnswer = answer(firstQuestion, "旧回答：两周");
        UUID originalAnswerId = firstAnswer.recentSteps().getLast().stepId();
        AgentSessionResponse oldSecondQuestion = advance(firstAnswer);
        AgentSessionResponse revised =
                service.reviseAnswer(
                        userId,
                        oldSecondQuestion.sessionId(),
                        UUID.randomUUID(),
                        new ReviseAgentAnswerRequest(
                                originalAnswerId,
                                json.node("新回答：两天"),
                                oldSecondQuestion.version()));
        assertEquals(4, stepRows.size());
        AgentStep newAnswer = stepRows.get(revised.recentSteps().getLast().stepId());
        assertEquals(firstQuestion.recentSteps().getLast().stepId(), newAnswer.getParentStepId());
        assertFalse(
                revised.recentSteps().stream()
                        .anyMatch(step -> step.stepId().equals(originalAnswerId)));
        assertFalse(
                revised.recentSteps().stream()
                        .anyMatch(
                                step ->
                                        step.stepId()
                                                .equals(
                                                        oldSecondQuestion
                                                                .recentSteps()
                                                                .getLast()
                                                                .stepId())));
        AtomicCapturedRequest captured = new AtomicCapturedRequest();
        FakeAgentModel fake = new FakeAgentModel(mapper);
        doAnswer(
                        invocation -> {
                            captured.value = invocation.getArgument(0);
                            return fake.analyze(captured.value);
                        })
                .when(model)
                .analyze(any());
        advance(revised);
        assertEquals(1, captured.value.history().size());
        assertEquals("新回答：两天", captured.value.history().getFirst().answer().asText());
        assertFalse(captured.value.state().toString().contains("旧回答"));
        assertEquals(3, sessionRows.get(revised.sessionId()).getTotalModelCalls());
    }

    @Test
    void lateModelResponseAfterPauseCannotAdvanceCheckpoint() {
        AgentSessionResponse created = create();
        FakeAgentModel fake = new FakeAgentModel(mapper);
        doAnswer(
                        invocation -> {
                            AgentSessionResponse running =
                                    service.getAgentSession(userId, created.sessionId());
                            assertEquals(AgentSessionStatus.RUNNING, running.status());
                            service.pauseSession(userId, running.sessionId(), version(running));
                            return fake.analyze(invocation.getArgument(0));
                        })
                .when(model)
                .analyze(any());
        AgentSessionResponse response = advance(created);
        assertEquals(AgentSessionStatus.PAUSED, response.status());
        assertEquals(created.activeCheckpointId(), response.activeCheckpointId());
        assertTrue(stepRows.isEmpty());
        assertEquals("DISCARDED", callRows.values().iterator().next().getStatus());
        assertEquals(1, response.totalModelCalls());
        assertEquals(1, usageRows.get(LocalDate.of(2026, 10, 27)));
    }

    @Test
    void failedModelCallConsumesBudgetAndRetainsCheckpoint() {
        settings.setMaxModelCalls(1);
        AgentSessionResponse created = create();
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "test"))
                .when(model)
                .analyze(any());
        assertCode("MODEL_CALL_FAILED", () -> advance(created));
        AgentSessionResponse failed = service.getAgentSession(userId, created.sessionId());
        assertEquals(AgentSessionStatus.FAILED, failed.status());
        assertEquals(1, failed.totalModelCalls());
        assertEquals(1, usageRows.get(LocalDate.of(2026, 10, 27)));
        assertEquals(created.activeCheckpointId(), failed.activeCheckpointId());
        assertEquals("FAILED", callRows.values().iterator().next().getStatus());
        assertCode(
                "MODEL_BUDGET_EXHAUSTED",
                () -> service.resumeSession(userId, failed.sessionId(), version(failed)));
        assertEquals(1, failed.totalModelCalls());
    }

    @Test
    void expiredRunningCanBeTakenOverButUnexpiredExecutionCannot() {
        AgentSessionResponse created = create();
        AgentSession stored = sessionRows.get(created.sessionId());
        stored.setStatus("RUNNING");
        stored.setExecutionDeadlineAt(
                OffsetDateTime.ofInstant(TIME.plusSeconds(30), ZoneOffset.UTC));
        stored.setExecutionGeneration(5L);
        stored.setTotalModelCalls(1);
        assertCode(
                "EXECUTION_STILL_RUNNING",
                () -> service.resumeSession(userId, created.sessionId(), version(created)));
        stored.setExecutionDeadlineAt(
                OffsetDateTime.ofInstant(TIME.minusSeconds(1), ZoneOffset.UTC));
        AgentSessionResponse resumed =
                service.resumeSession(userId, created.sessionId(), version(created));
        assertEquals(AgentSessionStatus.CREATED, resumed.status());
        assertEquals(1, resumed.totalModelCalls());
        assertEquals(6L, sessionRows.get(created.sessionId()).getExecutionGeneration());
        assertNull(sessionRows.get(created.sessionId()).getExecutionDeadlineAt());
    }

    @Test
    void finalizeDelegatesToHistoryAndLabelsConfiguredModelResultAsAi() {
        AgentDecision outcome =
                new AgentDecision(
                        AgentBehavior.PRODUCE_RESULT,
                        "离线测试结果",
                        null,
                        json.object(),
                        List.of(),
                        new AgentDecision.OutcomeDraft(
                                RecommendationLevel.ROUTINE_PROFESSIONAL,
                                "离线测试，仅整理输入",
                                List.of("测试资料"),
                                List.of("咨询专业人员"),
                                List.of(),
                                "仅演示"),
                        List.of());
        doReturn(new AgentModelResult(outcome, "test-request", 10, 5, 15))
                .when(model)
                .analyze(any());
        AgentSessionResponse completed = advance(create());
        assertEquals(AgentSessionStatus.COMPLETED, completed.status());
        UUID resultId = UUID.randomUUID();
        when(bridge.finalizeResult(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyInt(),
                        anyString(),
                        anyString()))
                .thenReturn(new AgentAssessmentBridge.FinalizedResult(resultId, 1, TIME));
        clearInvocations(model);
        AgentFinalizationResponse saved = service.finalizeOutcome(userId, completed.sessionId());
        assertEquals(resultId, saved.resultId());
        verify(bridge)
                .finalizeResult(
                        eq(userId),
                        eq(completed.sessionId()),
                        eq(completed.recentSteps().getLast().stepId()),
                        eq(completed.activeCheckpointId()),
                        any(),
                        eq("symptom-agent"),
                        eq(1),
                        eq("AI"),
                        eq("a".repeat(64)));
        verifyNoInteractions(model);
        assertEquals(1, completed.totalModelCalls());
        assertTrue(
                service.getAgentOutcome(userId, completed.sessionId())
                        .safetyNotice()
                        .contains("不是医学诊断"));
        assertCode(
                "SESSION_FINISHED",
                () ->
                        service.reviseAnswer(
                                userId,
                                completed.sessionId(),
                                UUID.randomUUID(),
                                new ReviseAgentAnswerRequest(
                                        UUID.randomUUID(), json.node("改答"), completed.version())));
    }

    @Test
    void changingModelKeepsPendingQuestionAndDoesNotResetBudget() {
        AgentSessionResponse waiting = advance(create());
        long generation = sessionRows.get(waiting.sessionId()).getExecutionGeneration();
        AgentSessionResponse changed =
                service.changeModel(
                        userId,
                        waiting.sessionId(),
                        new ChangeAgentModelRequest(waiting.version(), "qwen", "qwen3.7-flash"));
        assertEquals("qwen", changed.modelProvider());
        assertEquals("qwen3.7-flash", changed.modelName());
        assertEquals(AgentSessionStatus.WAITING_FOR_USER, changed.status());
        assertEquals(waiting.question().questionId(), changed.question().questionId());
        assertEquals(waiting.activeCheckpointId(), changed.activeCheckpointId());
        assertEquals(waiting.totalModelCalls(), changed.totalModelCalls());
        assertTrue(sessionRows.get(waiting.sessionId()).getExecutionGeneration() > generation);
        verify(model).requireAvailable("qwen", "qwen3.7-flash");
    }

    @Test
    void cancelledSessionIsReadOnlyAndReanalysisUsesNewAttempt() {
        AgentSessionResponse waiting = advance(create());
        AgentSessionResponse cancelled =
                service.cancelSession(userId, waiting.sessionId(), version(waiting));
        assertEquals(AgentSessionStatus.CANCELLED, cancelled.status());
        assertEquals(waiting.activeCheckpointId(), cancelled.activeCheckpointId());
        assertCode(
                "SESSION_FINISHED",
                () -> service.resumeSession(userId, cancelled.sessionId(), version(cancelled)));
        AgentSessionResponse next =
                service.createAgentSession(
                        userId,
                        UUID.randomUUID(),
                        new CreateAgentSessionRequest(
                                assessmentId,
                                "zh-CN",
                                null,
                                null,
                                cancelled.sessionId(),
                                cancelled.activeCheckpointId()));
        assertNotEquals(cancelled.sessionId(), next.sessionId());
        assertEquals(2, sessionRows.get(next.sessionId()).getAttemptNo());
        assertEquals(cancelled.sessionId(), sessionRows.get(next.sessionId()).getSourceSessionId());
        assertEquals(0, next.totalModelCalls());
        assertEquals(AgentSessionStatus.WAITING_FOR_USER, next.status());
        assertEquals(waiting.question().questionId(), next.question().questionId());
    }

    @Test
    void fullFakeWorkflowEndsAfterTwoAnswersAndPreservesAllSteps() {
        AgentSessionResponse firstQuestion = advance(create());
        AgentSessionResponse firstAnswer = answer(firstQuestion, "握持时有不适");
        AgentSessionResponse secondQuestion = advance(firstAnswer);
        AgentSessionResponse secondAnswer = answer(secondQuestion, "正常使用有轻微影响");
        AgentSessionResponse completed = advance(secondAnswer);
        assertEquals(AgentSessionStatus.COMPLETED, completed.status());
        assertEquals(3, completed.totalModelCalls());
        assertEquals(5, service.listSteps(userId, completed.sessionId()).size());
        assertTrue(
                service.getAgentOutcome(userId, completed.sessionId())
                        .summary()
                        .contains("离线演示结果"));
        assertCode("SESSION_FINISHED", () -> advance(completed));
    }

    @Test
    void corruptedCheckpointIsRejectedBeforeAnyModelCall() {
        AgentSessionResponse created = create();
        AgentCheckpoint point = checkpointRows.get(created.activeCheckpointId());
        ObjectNode changed = json.object(point.getState());
        changed.put("tampered", true);
        point.setState(json.pg(changed));
        clearInvocations(model);
        assertCode(
                "CHECKPOINT_CORRUPTED", () -> service.getAgentSession(userId, created.sessionId()));
        assertCode("CHECKPOINT_CORRUPTED", () -> advance(created));
        verifyNoInteractions(model);
    }

    @Test
    void exhaustedDailyQuotaStopsBeforePicturesModelAndSessionMutation() {
        AgentSessionResponse created = create();
        usageRows.put(LocalDate.of(2026, 10, 27), 10);
        clearInvocations(bridge, model, calls, sessions);

        ApiException failure = assertThrows(ApiException.class, () -> advance(created));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.status());
        assertEquals("AI_DAILY_QUOTA_EXCEEDED", failure.code());
        verifyNoInteractions(bridge);
        verify(model, never()).analyze(any());
        verify(calls, never()).insert(any(ModelCall.class));
        verify(sessions, never()).updateState(any());
        assertEquals(0, sessionRows.get(created.sessionId()).getTotalModelCalls());
        assertEquals(10, usageRows.get(LocalDate.of(2026, 10, 27)));
    }

    @Test
    void paymentFailureDoesNotReserveOrCallPicturesAndModel() {
        AgentSessionResponse created = create();
        when(payment.getEntitlement(userId)).thenThrow(new IllegalStateException("offline"));
        clearInvocations(usage, bridge, model, sessions, calls);

        ApiException failure = assertThrows(ApiException.class, () -> advance(created));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        assertEquals("PAYMENT_ENTITLEMENT_UNAVAILABLE", failure.code());
        verifyNoInteractions(usage, bridge, model, sessions, calls);
    }

    @Test
    void openPaymentCircuitDoesNotReserveOrCallPicturesAndModel() {
        AgentSessionResponse created = create();
        when(payment.getEntitlement(userId))
                .thenThrow(new UncheckedIOException(new ConnectException("offline")));
        clearInvocations(payment, usage, bridge, model, sessions, calls);
        for (int i = 0; i < 5; i++) {
            ApiException failure = assertThrows(ApiException.class, () -> advance(created));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        }
        doReturn(new EntitlementResponse(userId, "FREE", "免费", 10, null))
                .when(payment).getEntitlement(userId);

        ApiException blocked = assertThrows(ApiException.class, () -> advance(created));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, blocked.status());
        assertEquals("PAYMENT_ENTITLEMENT_UNAVAILABLE", blocked.code());
        assertTrue(blocked.getMessage().contains("temporarily blocked"));
        verify(payment, times(5)).getEntitlement(userId);
        verifyNoInteractions(usage, bridge, model, sessions, calls);
        assertTrue(usageRows.isEmpty());
    }

    @Test
    void newSessionKeepsUserDailyUsageAndCurrentUpgradeAddsOnlyRemainingAllowance() {
        when(payment.getEntitlement(userId))
                .thenReturn(new EntitlementResponse(userId, "FREE", "免费", 1, null));
        AgentSessionResponse first = advance(create());
        service.cancelSession(userId, first.sessionId(), version(first));
        AgentSessionResponse second = create();
        clearInvocations(model, bridge);

        assertCode("AI_DAILY_QUOTA_EXCEEDED", () -> advance(second));
        verifyNoInteractions(bridge);
        verify(model, never()).analyze(any());
        assertEquals(0, second.totalModelCalls());
        assertEquals(1, usageRows.get(LocalDate.of(2026, 10, 27)));

        when(payment.getEntitlement(userId)).thenReturn(new EntitlementResponse(
                userId, "PLUS", "PLUS", 50, TIME.plusSeconds(30L * 24 * 60 * 60)));
        AgentSessionResponse upgraded = advance(second);
        assertEquals(1, upgraded.totalModelCalls());
        assertEquals(2, usageRows.get(LocalDate.of(2026, 10, 27)));
    }

    @Test
    void pictureFailureKeepsTheCommittedDailyAttempt() {
        AgentSessionResponse created = create();
        when(bridge.resolvePictures(eq(userId), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_IMAGE_UNAVAILABLE", "test"));
        clearInvocations(model);

        assertCode("MODEL_IMAGE_UNAVAILABLE", () -> advance(created));

        verify(model, never()).analyze(any());
        assertEquals(1, usageRows.get(LocalDate.of(2026, 10, 27)));
        assertEquals("FAILED", callRows.values().iterator().next().getStatus());
    }

    private CreateAgentSessionRequest request() {
        return new CreateAgentSessionRequest(assessmentId, "zh-CN", null, null, null, null);
    }

    private AgentSessionResponse create() {
        return service.createAgentSession(userId, UUID.randomUUID(), request());
    }

    private AgentSessionResponse advance(AgentSessionResponse current) {
        return service.advanceSession(userId, current.sessionId(), version(current));
    }

    private AgentVersionRequest version(AgentSessionResponse current) {
        return new AgentVersionRequest(current.version());
    }

    private AgentSessionResponse answer(AgentSessionResponse waiting, String answer) {
        return service.answerAgentQuestion(
                userId,
                waiting.sessionId(),
                UUID.randomUUID(),
                new AnswerAgentQuestionRequest(
                        waiting.question().questionId(),
                        json.node(answer),
                        null,
                        waiting.version(),
                        waiting.activeCheckpointId()));
    }

    private AgentModelResult questionResult(AgentQuestion question) {
        return new AgentModelResult(
                new AgentDecision(
                        AgentBehavior.NARROW_DOWN,
                        question.prompt(),
                        question,
                        json.object(),
                        List.of(),
                        null,
                        List.of()),
                "test",
                null,
                null,
                null);
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ApiException.class, operation).code());
    }

    @SuppressWarnings("unchecked")
    private <T> T copy(T source) {
        if (source == null) return null;
        try {
            T target = (T) source.getClass().getConstructor().newInstance();
            BeanUtils.copyProperties(source, target);
            return target;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static class AtomicCapturedRequest {
        AgentModelRequest value;
    }
}
