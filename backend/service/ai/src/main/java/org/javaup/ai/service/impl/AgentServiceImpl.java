package org.javaup.ai.service.impl;

import static org.javaup.ai.api.problem.AIErrorCode.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentEnums.AgentAnswerType;
import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentEnums.AgentSessionStatus;
import org.javaup.ai.api.dto.AgentEnums.AgentStepType;
import org.javaup.ai.api.dto.AgentEnums.AgentTerminationReason;
import org.javaup.ai.api.dto.AgentEnums.ModelCallErrorCategory;
import org.javaup.ai.api.dto.AgentEnums.ModelCallStatus;
import org.javaup.ai.api.dto.AgentEnums.ResultGeneratedBy;
import org.javaup.ai.api.dto.AgentFinalizationResponse;
import org.javaup.ai.api.dto.AgentOutcomeResponse;
import org.javaup.ai.api.dto.AgentQuestion;
import org.javaup.ai.api.dto.AgentQuestionOption;
import org.javaup.ai.api.dto.AgentSessionResponse;
import org.javaup.ai.api.dto.AgentStepSummary;
import org.javaup.ai.api.dto.AgentVersionRequest;
import org.javaup.ai.api.dto.AnswerAgentQuestionRequest;
import org.javaup.ai.api.dto.ChangeAgentModelRequest;
import org.javaup.ai.api.dto.CreateAgentSessionRequest;
import org.javaup.ai.api.dto.ReviseAgentAnswerRequest;
import org.javaup.ai.api.problem.AIErrorCode;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.config.AgentWorkflowProperties;
import org.javaup.ai.client.AgentPaymentClient.EntitlementResponse;
import org.javaup.ai.entity.AgentCheckpoint;
import org.javaup.ai.entity.AgentSession;
import org.javaup.ai.entity.AgentStep;
import org.javaup.ai.entity.ModelCall;
import org.javaup.ai.mapper.AgentCheckpointMapper;
import org.javaup.ai.mapper.AgentSessionMapper;
import org.javaup.ai.mapper.AgentStepMapper;
import org.javaup.ai.mapper.ModelCallMapper;
import org.javaup.ai.model.AgentDecision;
import org.javaup.ai.model.AgentModelClient;
import org.javaup.ai.model.AgentModelRequest;
import org.javaup.ai.model.AgentModelResult;
import org.javaup.ai.service.AgentAssessmentBridge;
import org.javaup.ai.service.AgentJson;
import org.javaup.ai.service.AgentQuotaService;
import org.javaup.ai.service.AgentService;
import org.postgresql.util.PGobject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 阅读顺序：create → get → answer → advance → pause/resume → revise → finalize。
 * 写入先锁本库Session；远程History/Image/模型调用全部放在AI事务之外。
 */
@Service
public class AgentServiceImpl implements AgentService {
    private static final String SAFETY_NOTICE = "本结果仅辅助整理症状和寻求专业帮助，不是医学诊断。";

    private final AgentSessionMapper sessions;
    private final AgentStepMapper steps;
    private final AgentCheckpointMapper checkpoints;
    private final ModelCallMapper calls;
    private final TransactionTemplate transaction;
    private final AgentAssessmentBridge bridge;
    private final AgentModelClient modelClient;
    private final AgentQuotaService quota;
    private final AgentWorkflowProperties settings;
    private final AgentJson json;
    private final Clock clock;

    public AgentServiceImpl(
            AgentSessionMapper sessions,
            AgentStepMapper steps,
            AgentCheckpointMapper checkpoints,
            ModelCallMapper calls,
            TransactionTemplate agentTransaction,
            AgentAssessmentBridge bridge,
            AgentModelClient modelClient,
            AgentQuotaService quota,
            AgentWorkflowProperties settings,
            AgentJson json,
            Clock clock
    ) {
        this.sessions = sessions;
        this.steps = steps;
        this.checkpoints = checkpoints;
        this.calls = calls;
        this.transaction = agentTransaction;
        this.bridge = bridge;
        this.modelClient = modelClient;
        this.quota = quota;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public AgentSessionResponse createAgentSession(
            UUID userId,
            UUID key,
            CreateAgentSessionRequest request
    ) {
        require(
                userId != null
                        && key != null
                        && request != null
                        && request.assessmentId() != null,
                HttpStatus.BAD_REQUEST,
                INVALID_SESSION_REQUEST,
                "User, key and assessment are required");
        require(
                (request.sourceSessionId() == null) == (request.sourceCheckpointId() == null),
                HttpStatus.BAD_REQUEST,
                INVALID_SESSION_SOURCE,
                "Source session and checkpoint must be supplied together");

        String fingerprint = json.hash(request);

        // 幂等回放在外部调用与当前配置校验之前：重试不能重新拍一份输入快照。
        AgentSession previous = sessions.selectByRequest(userId, key);

        if (previous != null) {
            return transaction.execute(
                    tx -> replayCreate(owned(userId, previous.getId(), true), fingerprint));
        }

        String provider = defaulted(request.modelProvider(), settings.getDefaultProvider());
        String model = defaulted(request.modelName(), settings.getDefaultModel());
        modelClient.requireAvailable(provider, model);

        AgentAssessmentBridge.Snapshot snapshot = bridge.loadSnapshot(
                userId,
                request.assessmentId());

        return transaction.execute(tx -> {
            AgentSession replay = sessions.selectByRequest(userId, key);

            if (replay != null) {
                return replayCreate(replay, fingerprint);
            }

            ObjectNode input = (ObjectNode) snapshot.inputSnapshot().deepCopy();
            ObjectNode state = json.object();
            state.set("facts", json.object());
            state.set("answers", json.array());
            int inputVersion = snapshot.assessmentVersion();

            if (request.sourceSessionId() != null) {
                AgentSession source = owned(userId, request.sourceSessionId(), false);
                require(
                        source.getAssessmentId().equals(request.assessmentId())
                                && source.isTerminal(),
                        HttpStatus.CONFLICT,
                        INVALID_SESSION_SOURCE,
                        "Reanalysis requires a finished source of the same assessment");
                AgentCheckpoint sourcePoint = checkpoint(source, request.sourceCheckpointId());
                state = state(sourcePoint);
                state.remove("outcome");
                input = json.object(source.getInputSnapshot());
                inputVersion = source.getAssessmentVersion();
            }

            OffsetDateTime now = now();
            int attemptNo = sessions.nextAttempt(request.assessmentId());
            AgentSession session = AgentSession.create(
                    userId,
                    key,
                    request,
                    fingerprint,
                    inputVersion,
                    attemptNo,
                    json.pg(input),
                    provider,
                    model,
                    settings,
                    now);

            require(
                    session.getMaxModelCalls() > 0
                            && session.getMaxSteps() > 0
                            && session.getPreferredLocale().length() <= 20,
                    HttpStatus.BAD_REQUEST,
                    INVALID_SESSION_CONFIGURATION,
                    "Invalid locale or workflow budget");

            if (sessions.insertIfAbsent(session) == 0) {
                AgentSession winner = sessions.selectByRequest(userId, key);

                if (winner != null) {
                    return replayCreate(winner, fingerprint);
                }

                throw problem(
                        HttpStatus.CONFLICT,
                        ACTIVE_SESSION_EXISTS,
                        "An analysis is already active or its attempt was allocated concurrently; reload before retrying");
            }

            AgentCheckpoint initial = saveCheckpoint(session, null, state);
            session.setActiveCheckpointId(initial.getId());

            if (hasQuestion(state)) {
                session.setStatus(AgentSessionStatus.WAITING_FOR_USER.name());
            }

            saveSession(session);

            return response(session, initial);
        });
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentSessionResponse getAgentSession(
            UUID userId,
            UUID sessionId
    ) {
        AgentSession session = owned(userId, sessionId, false);

        return response(session, checkpoint(session, session.getActiveCheckpointId()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentSessionResponse getActiveSession(
            UUID userId,
            UUID assessmentId
    ) {
        require(
                userId != null && assessmentId != null,
                HttpStatus.BAD_REQUEST,
                INVALID_ASSESSMENT_ID,
                "User and assessment are required");
        AgentSession session = sessions.selectOpen(userId, assessmentId);
        require(
                session != null
                        && userId.equals(session.getUserId())
                        && assessmentId.equals(session.getAssessmentId()),
                HttpStatus.NOT_FOUND,
                ACTIVE_SESSION_NOT_FOUND,
                "No unfinished analysis exists for this assessment");

        return response(session, checkpoint(session, session.getActiveCheckpointId()));
    }

    @Override
    public AgentSessionResponse answerAgentQuestion(
            UUID userId,
            UUID sessionId,
            UUID key,
            AnswerAgentQuestionRequest request
    ) {
        require(
                key != null
                        && request != null
                        && request.questionId() != null
                        && request.baseCheckpointId() != null,
                HttpStatus.BAD_REQUEST,
                INVALID_AGENT_ANSWER,
                "Answer metadata is required");

        String fingerprint = json.hash(List.of(
                "answer",
                request.questionId(),
                request.baseCheckpointId(),
                json.node(request.answer())));

        return transaction.execute(tx -> {
            AgentSession session = owned(userId, sessionId, true);
            AgentStep replay = steps.selectByOperation(sessionId, key);

            if (replay != null) {
                return replayAnswer(session, replay, fingerprint);
            }

            expectVersion(session, request.expectedVersion());
            require(
                    AgentSessionStatus.WAITING_FOR_USER.name().equals(session.getStatus()),
                    HttpStatus.CONFLICT,
                    NOT_WAITING_FOR_ANSWER,
                    "This task is not waiting for an answer");
            require(
                    session.getActiveCheckpointId().equals(request.baseCheckpointId()),
                    HttpStatus.CONFLICT,
                    CHECKPOINT_CHANGED,
                    "The question belongs to a different current state");

            AgentCheckpoint base = checkpoint(session, request.baseCheckpointId());
            ObjectNode state = state(base);
            AgentQuestion question = question(state);
            require(
                    question != null && question.questionId().equals(request.questionId()),
                    HttpStatus.CONFLICT,
                    QUESTION_CHANGED,
                    "Reload the current question");

            return appendAnswer(
                    session, base, state, question, key, fingerprint, request.answer());
        });
    }

    @Override
    public AgentSessionResponse reviseAnswer(
            UUID userId,
            UUID sessionId,
            UUID key,
            ReviseAgentAnswerRequest request
    ) {
        require(
                key != null && request != null && request.answerStepId() != null,
                HttpStatus.BAD_REQUEST,
                INVALID_REVISION,
                "Answer step and key are required");

        String fingerprint = json.hash(List.of(
                "revise",
                request.answerStepId(),
                json.node(request.answer())));

        return transaction.execute(tx -> {
            AgentSession session = owned(userId, sessionId, true);
            AgentStep replay = steps.selectByOperation(sessionId, key);

            if (replay != null) {
                return replayAnswer(session, replay, fingerprint);
            }

            expectVersion(session, request.expectedVersion());
            editable(session);
            AgentStep original = steps.selectById(request.answerStepId());
            require(
                    original != null
                            && sessionId.equals(original.getSessionId())
                            && AgentStepType.USER_INPUT.name().equals(original.getStepType()),
                    HttpStatus.NOT_FOUND,
                    ANSWER_STEP_NOT_FOUND,
                    "Answer step does not exist in this task");

            AgentCheckpoint base =
                    original.getParentStepId() == null
                            ? checkpoints.selectInitial(sessionId)
                            : checkpoints.selectByStep(
                                    sessionId, original.getParentStepId());
            require(
                    base != null,
                    HttpStatus.CONFLICT,
                    ANSWER_BASE_MISSING,
                    "The state before the answer is unavailable");
            ObjectNode state = state(base);
            AgentQuestion question = question(state);
            require(
                    question != null,
                    HttpStatus.CONFLICT,
                    QUESTION_MISSING,
                    "The original question is unavailable");
            // 从旧回答之前的快照分叉；不加载旧回答之后的事实、问题或结论。
            return appendAnswer(
                    session, base, state, question, key, fingerprint, request.answer());
        });
    }

    @Override
    public AgentSessionResponse advanceSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    ) {
        Long expected = version(request);
        EntitlementResponse entitlement = quota.getEntitlement(userId);
        Execution execution = transaction.execute(
                tx -> reserveExecution(userId, sessionId, expected, entitlement));
        long started = System.nanoTime();
        AgentModelResult result;

        try {
            List<AgentModelRequest.Picture> pictures =
                    bridge.resolvePictures(userId, execution.input());
            ObjectNode modelState = execution.state().deepCopy();
            modelState.put(
                    "remainingModelCalls",
                    execution.session().getMaxModelCalls()
                            - execution.session().getTotalModelCalls());
            result =
                    modelClient.analyze(
                            new AgentModelRequest(
                                    sessionId,
                                    execution.session().getModelProvider(),
                                    execution.session().getModelName(),
                                    execution.session().getPreferredLocale(),
                                    execution.input(),
                                    modelState,
                                    execution.history(),
                                    pictures));
            validateDecision(result == null ? null : result.decision());
        } catch (RuntimeException failure) {
            transaction.executeWithoutResult(
                    tx -> recordFailure(userId, execution, elapsed(started), failure));

            if (failure instanceof ApiException api) {
                throw api;
            }

            throw problem(
                    HttpStatus.BAD_GATEWAY,
                    MODEL_CALL_FAILED,
                    "The analysis step failed; its saved state can be resumed");
        }

        AgentModelResult completed = result;

        return transaction.execute(
                tx -> completeExecution(userId, execution, completed, elapsed(started)));
    }

    @Override
    public AgentSessionResponse pauseSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    ) {
        return transaction.execute(tx -> {
            AgentSession session = owned(userId, sessionId, true);
            expectVersion(session, version(request));
            editable(session);

            if (!AgentSessionStatus.PAUSED.name().equals(session.getStatus())) {
                invalidateExecution(session);
                session.setStatus(AgentSessionStatus.PAUSED.name());
                session.touch(now());
                saveSession(session);
            }

            return response(session, checkpoint(session, session.getActiveCheckpointId()));
        });
    }

    @Override
    public AgentSessionResponse resumeSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    ) {
        return transaction.execute(tx -> {
            AgentSession session = owned(userId, sessionId, true);
            expectVersion(session, version(request));
            editable(session);

            AgentCheckpoint point = checkpoint(session, session.getActiveCheckpointId());

            if (AgentSessionStatus.RUNNING.name().equals(session.getStatus())) {
                require(
                        !session.getExecutionDeadlineAt().isAfter(now()),
                        HttpStatus.CONFLICT,
                        EXECUTION_STILL_RUNNING,
                        "The current execution has not expired");
            } else {
                require(
                        Set.of(
                                        AgentSessionStatus.PAUSED.name(),
                                        AgentSessionStatus.FAILED.name())
                                .contains(session.getStatus()),
                        HttpStatus.CONFLICT,
                        SESSION_NOT_RESUMABLE,
                        "Only paused, failed or expired executions need resuming");
            }

            ObjectNode state = state(point);

            if (!hasQuestion(state)) {
                requireBudget(session);
            }

            invalidateExecution(session);
            session.setStatus(
                    hasQuestion(state)
                            ? AgentSessionStatus.WAITING_FOR_USER.name()
                            : AgentSessionStatus.CREATED.name());
            session.setTerminationReasonCode(null);
            session.setTerminationDetail(null);
            session.setCompletedAt(null);
            session.touch(now());
            saveSession(session);

            return response(session, point);
        });
    }

    @Override
    public AgentSessionResponse cancelSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    ) {
        return transaction.execute(tx -> {
            AgentSession session = owned(userId, sessionId, true);
            expectVersion(session, version(request));
            require(
                    !AgentSessionStatus.COMPLETED.name().equals(session.getStatus()),
                    HttpStatus.CONFLICT,
                    SESSION_FINISHED,
                    "A completed task is read-only");

            if (!AgentSessionStatus.CANCELLED.name().equals(session.getStatus())) {
                invalidateExecution(session);
                session.setStatus(AgentSessionStatus.CANCELLED.name());
                session.setCompletedAt(now());
                session.setTerminationReasonCode(
                        AgentTerminationReason.USER_CANCELLED.name());
                session.setTerminationDetail("用户取消了本次分析");
                session.touch(now());
                saveSession(session);
            }

            return response(session, checkpoint(session, session.getActiveCheckpointId()));
        });
    }

    @Override
    public AgentSessionResponse changeModel(
            UUID userId,
            UUID sessionId,
            ChangeAgentModelRequest request
    ) {
        require(
                request != null,
                HttpStatus.BAD_REQUEST,
                INVALID_MODEL_SELECTION,
                "Model selection is required");
        modelClient.requireAvailable(request.provider(), request.model());

        return transaction.execute(tx -> {
            AgentSession session = owned(userId, sessionId, true);
            expectVersion(session, request.expectedVersion());
            editable(session);

            AgentCheckpoint point = checkpoint(session, session.getActiveCheckpointId());

            if (!Objects.equals(session.getModelProvider(), request.provider())
                    || !Objects.equals(session.getModelName(), request.model())) {
                boolean paused = AgentSessionStatus.PAUSED.name().equals(session.getStatus());
                invalidateExecution(session);
                session.setModelProvider(request.provider());
                session.setModelName(request.model());

                if (paused) {
                    session.setStatus(AgentSessionStatus.PAUSED.name());
                } else if (hasQuestion(state(point))) {
                    session.setStatus(AgentSessionStatus.WAITING_FOR_USER.name());
                } else {
                    session.setStatus(AgentSessionStatus.CREATED.name());
                }

                session.setTerminationReasonCode(null);
                session.setTerminationDetail(null);
                session.setCompletedAt(null);
                session.touch(now());
                saveSession(session);
            }

            return response(session, point);
        });
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AgentStepSummary> listSteps(
            UUID userId,
            UUID sessionId
    ) {
        owned(userId, sessionId, false);

        return steps.selectSessionSteps(sessionId).stream()
                .map(AgentStepSummary::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentOutcomeResponse getAgentOutcome(
            UUID userId,
            UUID sessionId
    ) {
        AgentSession session = owned(userId, sessionId, false);

        return outcome(session, checkpoint(session, session.getActiveCheckpointId()));
    }

    @Override
    public AgentFinalizationResponse finalizeOutcome(
            UUID userId,
            UUID sessionId
    ) {
        // 先读已冻结的候选，再调用History；不尝试把两个数据库包进同一个事务。
        Finalization data = transaction.execute(tx -> {
            AgentSession session = owned(userId, sessionId, false);

            AgentCheckpoint point = checkpoint(session, session.getActiveCheckpointId());

            return new Finalization(session, point, outcome(session, point));
        });

        String inputFingerprint =
                json.object(data.session().getInputSnapshot())
                        .path("assessmentInputFingerprint")
                        .asText();
        AgentAssessmentBridge.FinalizedResult saved =
                bridge.finalizeResult(
                        userId,
                        sessionId,
                        data.point().getStepId(),
                        data.point().getId(),
                        data.outcome(),
                        data.session().getWorkflowVersion(),
                        data.session().getAttemptNo(),
                        "fake".equals(data.session().getModelProvider())
                                ? ResultGeneratedBy.MOCK.name()
                                : ResultGeneratedBy.AI.name(),
                        inputFingerprint);

        return new AgentFinalizationResponse(
                data.session().getAssessmentId(),
                sessionId,
                saved.resultId(),
                saved.revisionNo(),
                saved.finalizedAt());
    }

    private Execution reserveExecution(
            UUID userId,
            UUID sessionId,
            Long expected,
            EntitlementResponse entitlement
    ) {
        AgentSession session = owned(userId, sessionId, true);
        expectVersion(session, expected);
        editable(session);
        require(
                Set.of(AgentSessionStatus.CREATED.name(), AgentSessionStatus.FAILED.name())
                        .contains(session.getStatus()),
                HttpStatus.CONFLICT,
                SESSION_NOT_READY,
                "Save an answer or resume the task before advancing");

        AgentCheckpoint base = checkpoint(session, session.getActiveCheckpointId());
        ObjectNode state = state(base);
        require(
                !hasQuestion(state),
                HttpStatus.CONFLICT,
                ANSWER_REQUIRED,
                "Answer the saved question before advancing");
        requireBudget(session);
        modelClient.requireAvailable(session.getModelProvider(), session.getModelName());

        quota.reserve(userId, entitlement);
        invalidateExecution(session);
        session.beginExecution(now(), settings.getExecutionTimeoutSeconds());
        saveSession(session);

        PGobject responseMetadata = json.pg(json.object());
        OffsetDateTime startedAt = now();
        OffsetDateTime createdAt = now();
        String requestFingerprint = json.hash(List.of(
                json.object(session.getInputSnapshot()),
                state,
                session.getModelProvider(),
                session.getModelName()));
        ModelCall call = ModelCall.start(
                session,
                base.getId(),
                requestFingerprint,
                responseMetadata,
                startedAt,
                createdAt);
        calls.insert(call);

        List<AgentModelRequest.Turn> history = new ArrayList<>();
        // 分支上下文来自起点快照；不按step_no混入旧分支。
        for (JsonNode answer : state.path("answers")) {
            history.add(new AgentModelRequest.Turn(
                    AgentStepType.USER_INPUT.name(),
                    answer.path("questionText").asText(),
                    answer.path("answer")));
        }

        return new Execution(
                session,
                base,
                state,
                json.object(session.getInputSnapshot()),
                call,
                List.copyOf(history));
    }

    private AgentSessionResponse completeExecution(
            UUID userId,
            Execution execution,
            AgentModelResult result,
            int latency
    ) {
        AgentSession session = owned(userId, execution.session().getId(), true);
        ModelCall call = execution.call();
        call.recordResponse(result, latency, now());

        if (!current(session, execution)) {
            call.setStatus(ModelCallStatus.DISCARDED.name());
            calls.updateById(call);

            return response(session, checkpoint(session, session.getActiveCheckpointId()));
        }

        AgentDecision decision = result.decision();
        ObjectNode next = execution.state().deepCopy();
        ObjectNode facts =
                next.path("facts").isObject() ? (ObjectNode) next.path("facts") : json.object();

        if (decision.extractedFacts() != null) {
            decision.extractedFacts()
                    .properties()
                    .forEach(entry -> facts.set(entry.getKey(), entry.getValue()));
        }

        next.set("facts", facts);
        next.put("behavior", decision.behavior().name());
        next.set(
                "references",
                json.node(decision.references() == null ? List.of() : decision.references()));

        AgentStepType type = switch (decision.behavior()) {
            case PRODUCE_RESULT -> AgentStepType.OUTPUT_RESULT;
            case CLARIFY_CONTRADICTION -> AgentStepType.REQUEST_CONFIRMATION;
            case NARROW_DOWN -> AgentStepType.NARROW_DOWN;
        };
        AgentStep step = newStep(session, execution.base(), type);

        ObjectNode safeDecision = json.object();
        safeDecision.put("behavior", decision.behavior().name());
        List<String> contradictionCodes =
                decision.contradictionCodes() == null ? List.of() : decision.contradictionCodes();
        step.recordDecision(
                decision.message(),
                json.pg(facts),
                json.pg(json.node(contradictionCodes)),
                json.pg(safeDecision));

        boolean resultReady = decision.behavior() == AgentBehavior.PRODUCE_RESULT;
        if (resultReady) {
            AgentOutcomeResponse output = AgentOutcomeResponse.fromDecision(
                    session,
                    decision.outcome(),
                    SAFETY_NOTICE,
                    now().toInstant());
            next.set("outcome", json.node(output));
            next.remove("pendingQuestion");
            step.complete(now());
        } else {
            AgentQuestion question = decision.question().withIdIfMissing();
            next.set("pendingQuestion", json.node(question));
            step.awaitAnswer(question, json.pg(json.node(question)), now());
        }
        steps.insert(step);

        AgentCheckpoint point = saveCheckpoint(session, step, next);
        AgentSessionStatus nextStatus = resultReady
                ? AgentSessionStatus.COMPLETED
                : AgentSessionStatus.WAITING_FOR_USER;
        session.acceptCheckpoint(point.getId(), nextStatus, now());
        saveSession(session);

        call.markSucceeded(step.getId());
        calls.updateById(call);

        return response(session, point);
    }

    private AgentSessionResponse appendAnswer(
            AgentSession session,
            AgentCheckpoint base,
            ObjectNode state,
            AgentQuestion question,
            UUID key,
            String fingerprint,
            JsonNode answer
    ) {
        validateAnswer(question, answer);
        require(
                session.hasStepBudget(),
                HttpStatus.CONFLICT,
                STEP_BUDGET_EXHAUSTED,
                "No further steps are available in this task");
        invalidateExecution(session);

        AgentStep step = newStep(session, base, AgentStepType.USER_INPUT);
        step.recordAnswer(question, key, fingerprint, json.pg(answer), now());
        steps.insert(step);

        ArrayNode answers =
                state.path("answers").isArray() ? (ArrayNode) state.path("answers") : json.array();
        ObjectNode saved = json.object();
        saved.put("questionId", question.questionId().toString());
        saved.put("questionKey", question.questionKey());
        saved.put("questionText", question.prompt());
        saved.set("answer", answer);
        answers.add(saved);
        state.set("answers", answers);
        state.remove("pendingQuestion");
        state.remove("outcome");

        AgentCheckpoint point = saveCheckpoint(session, step, state);
        session.acceptCheckpoint(point.getId(), AgentSessionStatus.CREATED, now());
        saveSession(session);

        return response(session, point);
    }

    private void recordFailure(
            UUID userId,
            Execution execution,
            int latency,
            RuntimeException failure
    ) {
        AgentSession session = owned(userId, execution.session().getId(), true);
        ModelCall call = execution.call();
        ModelCallErrorCategory category;
        String errorCode;

        if (failure instanceof ApiException api) {
            category = ModelCallErrorCategory.PROVIDER_OR_INPUT;
            errorCode = api.code();
        } else {
            category = ModelCallErrorCategory.MODEL_TRANSPORT_OR_OUTPUT;
            errorCode = MODEL_CALL_FAILED.code();
        }
        call.recordFailure(category, errorCode, latency, now());

        if (current(session, execution)) {
            call.setStatus(ModelCallStatus.FAILED.name());
            session.failExecution(errorCode, now());
            saveSession(session);
        } else {
            call.setStatus(ModelCallStatus.DISCARDED.name());
        }
        calls.updateById(call);
    }

    private AgentStep newStep(
            AgentSession session,
            AgentCheckpoint base,
            AgentStepType type
    ) {
        AgentStep parent = base.getStepId() == null ? null : steps.selectById(base.getStepId());
        session.setLastStepNo(session.getLastStepNo() + 1);

        AgentStep step = AgentStep.create(session, parent, type, now());
        step.setInputReferences(json.pg(json.object()));
        step.setExtractedFacts(json.pg(json.object()));
        step.setDecision(json.pg(json.object()));
        step.setContradictionCodes(json.pg(json.array()));

        return step;
    }

    private AgentCheckpoint saveCheckpoint(
            AgentSession session,
            AgentStep step,
            ObjectNode state
    ) {
        AgentCheckpoint point = AgentCheckpoint.create(
                session.getId(),
                step == null ? null : step.getId(),
                checkpoints.nextNumber(session.getId()),
                json.pg(state),
                json.hash(state),
                now());
        checkpoints.insert(point);

        return point;
    }

    private AgentSession owned(
            UUID userId,
            UUID sessionId,
            boolean lock
    ) {
        require(
                userId != null && sessionId != null,
                HttpStatus.BAD_REQUEST,
                INVALID_SESSION_ID,
                "User and session are required");
        AgentSession session =
                lock
                        ? sessions.selectOwnedForUpdate(userId, sessionId)
                        : sessions.selectById(sessionId);
        require(
                session != null && userId.equals(session.getUserId()),
                HttpStatus.NOT_FOUND,
                AGENT_SESSION_NOT_FOUND,
                "Analysis does not exist");

        return session;
    }

    private AgentCheckpoint checkpoint(
            AgentSession session,
            UUID id
    ) {
        AgentCheckpoint point = id == null ? null : checkpoints.selectById(id);
        require(
                point != null && session.getId().equals(point.getSessionId()),
                HttpStatus.CONFLICT,
                CHECKPOINT_NOT_FOUND,
                "Saved analysis state is unavailable");

        return point;
    }

    private ObjectNode state(AgentCheckpoint point) {
        require(
                point.getStateSchemaVersion() == 1,
                HttpStatus.CONFLICT,
                STATE_SCHEMA_UNSUPPORTED,
                "This saved workflow version is not supported");
        ObjectNode state = json.object(point.getState());
        require(
                json.hash(state).equals(point.getStateChecksum()),
                HttpStatus.CONFLICT,
                CHECKPOINT_CORRUPTED,
                "Saved state checksum does not match");

        return state;
    }

    private AgentSessionResponse response(
            AgentSession session,
            AgentCheckpoint point
    ) {
        ObjectNode state = state(point);
        List<AgentStep> path = activePath(session, point);
        String behavior = state.path("behavior").asText(null);

        return AgentSessionResponse.fromEntity(
                session,
                point,
                behavior == null ? null : AgentBehavior.valueOf(behavior),
                question(state),
                path);
    }

    private List<AgentStep> activePath(
            AgentSession session,
            AgentCheckpoint point
    ) {
        Map<UUID, AgentStep> all = new HashMap<>();

        for (AgentStep step : steps.selectSessionSteps(session.getId())) {
            all.put(step.getId(), step);
        }

        LinkedList<AgentStep> path = new LinkedList<>();
        Set<UUID> visited = new HashSet<>();
        UUID id = point.getStepId();

        while (id != null) {
            AgentStep step = all.get(id);
            require(
                    step != null && visited.add(id),
                    HttpStatus.CONFLICT,
                    INVALID_STEP_PATH,
                    "The saved branch cannot be read");
            path.addFirst(step);
            id = step.getParentStepId();
        }

        return path;
    }

    private AgentOutcomeResponse outcome(
            AgentSession session,
            AgentCheckpoint point
    ) {
        require(
                AgentSessionStatus.COMPLETED.name().equals(session.getStatus()),
                HttpStatus.CONFLICT,
                OUTCOME_NOT_READY,
                "The analysis has no completed outcome");
        JsonNode output = state(point).path("outcome");
        require(
                output.isObject(),
                HttpStatus.CONFLICT,
                OUTCOME_NOT_READY,
                "The saved outcome is unavailable");

        return json.read(output, AgentOutcomeResponse.class);
    }

    private AgentSessionResponse replayCreate(
            AgentSession session,
            String fingerprint
    ) {
        require(
                fingerprint.equals(session.getRequestFingerprint()),
                HttpStatus.CONFLICT,
                IDEMPOTENCY_KEY_REUSED,
                "The creation key was used with different content");

        return response(session, checkpoint(session, session.getActiveCheckpointId()));
    }

    private AgentSessionResponse replayAnswer(
            AgentSession current,
            AgentStep original,
            String fingerprint
    ) {
        require(
                fingerprint.equals(original.getPayloadFingerprint()),
                HttpStatus.CONFLICT,
                IDEMPOTENCY_KEY_REUSED,
                "The answer key was used with different content");
        // 返回当前状态，客户端不会因旧请求回放而退回旧分支；原节点仍可通过 /steps 查到。
        return response(current, checkpoint(current, current.getActiveCheckpointId()));
    }

    private void invalidateExecution(AgentSession session) {
        calls.discardInflight(session.getId(), session.getExecutionGeneration(), now());
        session.invalidateExecution();
    }

    private boolean current(
            AgentSession current,
            Execution old
    ) {
        return AgentSessionStatus.RUNNING.name().equals(current.getStatus())
                && current.getExecutionGeneration().equals(old.call().getExecutionGeneration())
                && current.getActiveCheckpointId().equals(old.base().getId());
    }

    private void requireBudget(AgentSession session) {
        require(
                session.hasModelCallBudget(),
                HttpStatus.CONFLICT,
                MODEL_BUDGET_EXHAUSTED,
                "Model-call budget is exhausted; create a new attempt deliberately");
        require(
                session.hasStepBudget(),
                HttpStatus.CONFLICT,
                STEP_BUDGET_EXHAUSTED,
                "Step budget is exhausted");
    }

    private void expectVersion(
            AgentSession session,
            Long expected
    ) {
        require(
                expected != null && expected > 0,
                HttpStatus.BAD_REQUEST,
                EXPECTED_VERSION_REQUIRED,
                "A positive expectedVersion is required");
        require(
                expected.equals(session.getVersion()),
                HttpStatus.CONFLICT,
                SESSION_VERSION_CONFLICT,
                "The analysis changed; reload before editing");
    }

    private void editable(AgentSession session) {
        require(
                !session.isTerminal(),
                HttpStatus.CONFLICT,
                SESSION_FINISHED,
                "Finished tasks are read-only; create a new analysis attempt");
    }

    private void saveSession(AgentSession session) {
        if (sessions.updateState(session) != 1) {
            throw new IllegalStateException("Locked Agent session disappeared");
        }
    }

    private Long version(AgentVersionRequest request) {
        require(
                request != null,
                HttpStatus.BAD_REQUEST,
                EXPECTED_VERSION_REQUIRED,
                "expectedVersion is required");

        return request.expectedVersion();
    }

    private boolean hasQuestion(ObjectNode state) {
        return state.path("pendingQuestion").isObject();
    }

    private AgentQuestion question(ObjectNode state) {
        return hasQuestion(state)
                ? json.read(state.path("pendingQuestion"), AgentQuestion.class)
                : null;
    }

    private String defaulted(
            String value,
            String fallback
    ) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private int elapsed(long started) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
    }

    private ApiException problem(
            HttpStatus status,
            AIErrorCode code,
            String detail
    ) {
        return new ApiException(status, code, detail);
    }

    private void require(
            boolean ok,
            HttpStatus status,
            AIErrorCode code,
            String detail
    ) {
        if (!ok) {
            throw problem(status, code, detail);
        }
    }

    private void validateAnswer(
            AgentQuestion question,
            JsonNode answer
    ) {
        require(
                answer != null
                        && !answer.isNull()
                        && !answer.isMissingNode()
                        && json.node(answer).toString().length() <= 8000,
                HttpStatus.BAD_REQUEST,
                INVALID_AGENT_ANSWER,
                "A bounded, non-null answer is required");

        Set<String> choices = new HashSet<>();

        if (question.options() != null) {
            question.options().forEach(o -> choices.add(o.value()));
        }

        boolean valid = switch (question.answerType()) {
            case TEXT -> answer.isTextual()
                    && !answer.asText().isBlank()
                    && answer.asText().length() <= 2000;
            case BOOLEAN -> answer.isBoolean();
            case SINGLE_CHOICE -> answer.isTextual()
                    && choices.contains(answer.asText());
            case MULTI_CHOICE -> {
                Set<String> selected = new HashSet<>();
                boolean accepted = answer.isArray()
                        && !answer.isEmpty()
                        && answer.size() <= 12;

                if (accepted) {
                    for (JsonNode value : answer) {
                        accepted &= value.isTextual()
                                && choices.contains(value.asText())
                                && selected.add(value.asText());
                    }
                }

                yield accepted;
            }
        };

        require(
                valid,
                HttpStatus.BAD_REQUEST,
                ANSWER_TYPE_MISMATCH,
                "Answer must match the saved question type and options");
    }

    private void validateDecision(AgentDecision decision) {
        require(
                decision != null
                        && decision.behavior() != null
                        && decision.message() != null
                        && !decision.message().isBlank()
                        && decision.message().length() <= 2000
                        && json.node(decision).toString().length() <= 64000,
                HttpStatus.BAD_GATEWAY,
                INVALID_MODEL_OUTPUT,
                "The model returned an invalid bounded decision");
        require(
                decision.extractedFacts() == null || decision.extractedFacts().isObject(),
                HttpStatus.BAD_GATEWAY,
                INVALID_MODEL_OUTPUT,
                "Extracted facts must be an object");

        if (decision.behavior() == AgentBehavior.PRODUCE_RESULT) {
            var o = decision.outcome();

            require(
                    o != null
                            && o.recommendationLevel() != null
                            && o.summary() != null
                            && !o.summary().isBlank()
                            && o.summary().length() <= 2000
                            && validList(o.reasons())
                            && validList(o.suggestedNextActions())
                            && validList(o.warningSigns()),
                    HttpStatus.BAD_GATEWAY,
                    INVALID_MODEL_OUTPUT,
                    "The model returned an invalid outcome");
        } else {
            AgentQuestion q = decision.question();

            require(
                    q != null
                            && q.answerType() != null
                            && q.questionKey() != null
                            && q.questionKey().matches("^[a-z][a-z0-9_.-]{1,79}$")
                            && q.prompt() != null
                            && !q.prompt().isBlank()
                            && q.prompt().length() <= 500,
                    HttpStatus.BAD_GATEWAY,
                    INVALID_MODEL_OUTPUT,
                    "The model returned an invalid question");

            if (q.answerType() == AgentAnswerType.SINGLE_CHOICE
                    || q.answerType() == AgentAnswerType.MULTI_CHOICE) {
                require(
                        q.options() != null
                                && !q.options().isEmpty()
                                && q.options().size() <= 12
                                && q.options().stream().allMatch(o ->
                                        o != null
                                                && o.value() != null
                                                && !o.value().isBlank()
                                                && o.value().length() <= 100
                                                && o.label() != null
                                                && !o.label().isBlank()
                                                && o.label().length() <= 200)
                                && q.options().stream()
                                        .map(AgentQuestionOption::value)
                                        .distinct()
                                        .count() == q.options().size(),
                        HttpStatus.BAD_GATEWAY,
                        INVALID_MODEL_OUTPUT,
                        "Choice questions require unique bounded options");
            }
        }
    }

    private boolean validList(List<String> values) {
        return values != null
                && values.size() <= 20
                && values.stream().allMatch(v ->
                        v != null
                                && !v.isBlank()
                                && v.length() <= 500);
    }

    private record Execution(
            AgentSession session,
            AgentCheckpoint base,
            ObjectNode state,
            ObjectNode input,
            ModelCall call,
            List<AgentModelRequest.Turn> history
    ) {}

    private record Finalization(
            AgentSession session,
            AgentCheckpoint point,
            AgentOutcomeResponse outcome
    ) {}
}
