package org.javaup.history.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.javaup.history.api.dto.AppendAssessmentAnswerRequest;
import org.javaup.history.api.dto.AssessmentAnswerResponse;
import org.javaup.history.api.dto.AssessmentResponse;
import org.javaup.history.api.dto.AssessmentResultResponse;
import org.javaup.history.api.dto.AssessmentResumeResponse;
import org.javaup.history.api.dto.HistoryEnums.AssessmentAnswerSource;
import org.javaup.history.api.dto.HistoryEnums.AssessmentStatus;
import org.javaup.history.api.dto.HistoryEnums.CurrentStepKey;
import org.javaup.history.api.dto.HistoryEnums.ResultGeneratedBy;
import org.javaup.history.api.dto.HistoryEnums.HandSide;
import org.javaup.history.api.dto.HistoryEnums.FingerCode;
import org.javaup.history.api.dto.HistoryEnums.JointCode;
import org.javaup.history.api.dto.ReplaceSymptomLocationsRequest;
import org.javaup.history.api.dto.SymptomLocationInput;
import org.javaup.history.api.dto.SubmitAssessmentRequest;
import org.javaup.history.api.problem.ApiException;
import org.javaup.history.client.ImageClient;
import org.javaup.history.entity.Assessment;
import org.javaup.history.entity.AssessmentAnswer;
import org.javaup.history.entity.AssessmentResult;
import org.javaup.history.entity.AssessmentSymptom;
import org.javaup.history.entity.AssessmentVisual;
import org.javaup.history.entity.SymptomLocation;
import org.javaup.history.mapper.AssessmentAnswerMapper;
import org.javaup.history.mapper.AssessmentMapper;
import org.javaup.history.mapper.AssessmentResultMapper;
import org.javaup.history.mapper.AssessmentSymptomMapper;
import org.javaup.history.mapper.AssessmentVisualMapper;
import org.javaup.history.mapper.SymptomLocationMapper;
import org.javaup.history.service.AssessmentAnswerService;
import org.javaup.history.service.AssessmentResultService;
import org.javaup.history.service.AssessmentSymptomService;
import org.javaup.history.service.AssessmentVisualService;
import org.javaup.history.service.SymptomLocationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests business decisions and scoped SQL predicates with mocked persistence.
 * Transaction annotations are checked, but real PostgreSQL locking/rollback is
 * intentionally not claimed by direct Service invocations in these tests.
 */
class AssessmentLifecycleServiceTest {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-10-25T00:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private AssessmentMapper assessmentMapper;
    private AssessmentAnswerMapper answerMapper;
    private AssessmentResultMapper resultMapper;
    private SymptomLocationMapper locationMapper;
    private AssessmentVisualMapper visualMapper;
    private AssessmentAnswerService answerService;
    private AssessmentResultService resultService;
    private SymptomLocationService locationService;
    private ImageClient imageClient;
    private AssessmentServiceImpl service;
    private Assessment assessment;

    @BeforeAll
    static void initializeLambdaMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(Assessment.class, AssessmentAnswer.class, AssessmentResult.class,
                AssessmentSymptom.class, AssessmentVisual.class, SymptomLocation.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "lifecycle-unit-test");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        assessmentMapper = mock(AssessmentMapper.class);
        answerMapper = mock(AssessmentAnswerMapper.class);
        resultMapper = mock(AssessmentResultMapper.class);
        locationMapper = mock(SymptomLocationMapper.class);
        visualMapper = mock(AssessmentVisualMapper.class);
        AssessmentSymptomMapper symptomMapper = mock(AssessmentSymptomMapper.class);
        answerService = mock(AssessmentAnswerService.class);
        resultService = mock(AssessmentResultService.class);
        AssessmentSymptomService symptomService = mock(AssessmentSymptomService.class);
        AssessmentVisualService visualService = mock(AssessmentVisualService.class);
        locationService = mock(SymptomLocationService.class);
        imageClient = mock(ImageClient.class);

        when(answerService.lambdaQuery()).thenAnswer(invocation ->
                new LambdaQueryChainWrapper<>(answerMapper, AssessmentAnswer.class));
        when(resultService.lambdaQuery()).thenAnswer(invocation ->
                new LambdaQueryChainWrapper<>(resultMapper, AssessmentResult.class));
        when(symptomService.lambdaQuery()).thenAnswer(invocation ->
                new LambdaQueryChainWrapper<>(symptomMapper, AssessmentSymptom.class));
        when(visualService.lambdaQuery()).thenAnswer(invocation ->
                new LambdaQueryChainWrapper<>(visualMapper, AssessmentVisual.class));
        when(locationService.lambdaQuery()).thenAnswer(invocation ->
                new LambdaQueryChainWrapper<>(locationMapper, SymptomLocation.class));

        when(answerService.getBaseMapper()).thenReturn(answerMapper);
        when(resultService.getBaseMapper()).thenReturn(resultMapper);
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(visualMapper.selectList(any())).thenReturn(List.of());
        when(symptomMapper.selectList(any())).thenReturn(List.of());
        when(locationMapper.selectList(any())).thenReturn(List.of(location()));
        when(locationMapper.exists(any())).thenReturn(true);
        when(locationMapper.selectCount(any())).thenReturn(1L);
        when(answerMapper.exists(any())).thenReturn(false);
        when(answerMapper.selectCount(any())).thenReturn(0L);
        when(visualMapper.exists(any())).thenReturn(false);
        when(visualMapper.selectCount(any())).thenReturn(0L);
        when(assessmentMapper.update(isNull(), any())).thenReturn(1);
        when(answerMapper.insertIfAbsent(any())).thenReturn(1);

        service = new AssessmentServiceImpl(answerService, resultService, symptomService, visualService,
                locationService, assessmentMapper, objectMapper, imageClient, mock(TransactionTemplate.class),
                answerMapper);
        ReflectionTestUtils.setField(service, "baseMapper", assessmentMapper);
        ReflectionTestUtils.setField(service, "entityClass", Assessment.class);

        assessment = assessment();
        when(assessmentMapper.selectOne(any())).thenReturn(assessment);
    }

    @Test
    void resumeReturnsOwnedAggregateAndSavedStepWithoutInventingAnAgentSession() {
        assessment.setCurrentStepKey("VISUAL_ANNOTATION");
        when(assessmentMapper.selectOne(any())).thenAnswer(invocation -> {
            assertOwnedScope(invocation.getArgument(0));
            return assessment;
        });

        AssessmentResumeResponse response = service.resume(userId, assessmentId);

        assertEquals(assessmentId, response.assessment().assessmentId());
        assertEquals(CurrentStepKey.VISUAL_ANNOTATION, response.currentStepKey());
        assertEquals(response.currentStepKey(), response.assessment().currentStepKey());
        assertNull(response.activeAgentSessionId());
        verify(assessmentMapper, never()).update(isNull(), any());
        verifyNoInteractions(imageClient);
    }

    @Test
    void resumeRejectsCancelledAndUnknownAssessments() {
        assessment.setStatus("CANCELLED");
        assertStatus(HttpStatus.CONFLICT, () -> service.resume(userId, assessmentId));
        when(assessmentMapper.selectOne(any())).thenReturn(null);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.resume(userId, assessmentId));
        verifyNoInteractions(imageClient);
    }

    @Test
    void completedAssessmentResumesAtResultRatherThanAnEditableForm() {
        assessment.setStatus("COMPLETED");
        assessment.setCompletedAt(CREATED_AT.plusHours(1));
        assessment.setCurrentStepKey("AGENT");

        AssessmentResumeResponse response = service.resume(userId, assessmentId);

        assertEquals(CurrentStepKey.RESULT, response.currentStepKey());
        assertEquals(CurrentStepKey.RESULT, response.assessment().currentStepKey());
        assertNull(response.activeAgentSessionId());
    }

    @Test
    void resultReturnsPersistedProvenanceAndSafetyNoticeWithoutGeneratingAnything() {
        assessment.setStatus("COMPLETED");
        AssessmentResult stored = result();
        when(resultService.getById(assessmentId)).thenReturn(stored);

        AssessmentResultResponse response = service.getResult(userId, assessmentId);

        assertEquals(assessmentId, response.assessmentId());
        assertEquals(ResultGeneratedBy.MOCK, response.generatedBy());
        assertEquals(stored.getSafetyNotice(), response.safetyNotice());
        assertEquals(List.of("位置描述有待进一步确认"), response.reasons());
        assertEquals(List.of("携带本次记录咨询专业人员"), response.suggestedNextActions());
        assertTrue(response.warningSigns().isEmpty());
        assertEquals(stored.getFinalizedAt().toInstant(), response.finalizedAt());
        verifyNoInteractions(imageClient);
        verify(resultMapper, never()).insert(any(AssessmentResult.class));
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void resultIsNotReadyUntilCompletedAndMissingResultNeverBecomesFakeSuccess() {
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getResult(userId, assessmentId));
        assessment.setStatus("COMPLETED");
        when(resultMapper.selectOne(any())).thenReturn(null);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getResult(userId, assessmentId));
        when(assessmentMapper.selectOne(any())).thenReturn(null);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getResult(userId, assessmentId));
        verify(resultMapper, never()).insert(any(AssessmentResult.class));
        verifyNoInteractions(imageClient);
    }

    @Test
    void firstSubmitLocksOwnedRootAndDoesNotTreatAnAgentNavigationStepAsSubmission() {
        assessment.setStatus("DRAFT");
        assessment.setCurrentStepKey("AGENT");
        when(assessmentMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            assertOwnedScope(query);
            assertTrue(query.getSqlSegment().toUpperCase().contains("FOR UPDATE"));
            return assessment;
        });

        AssessmentResponse response = service.submit(userId, assessmentId, new SubmitAssessmentRequest(7));

        assertEquals(8, response.version());
        assertEquals(AssessmentStatus.COLLECTING, response.status());
        assertEquals(CurrentStepKey.AGENT, response.currentStepKey());
        assertNull(response.completedAt());
        assertNotNull(assessment.getSubmittedAt());
        assertEquals(7, assessment.getLastSubmitVersion());
        verify(assessmentMapper).update(isNull(), any());
        verifyNoInteractions(imageClient);
        verify(resultMapper, never()).insert(any(AssessmentResult.class));
    }

    @Test
    void exactSubmissionReplayStillWorksAfterCompletionWithoutAnotherVersionIncrement() {
        assessment.setStatus("COMPLETED");
        assessment.setVersion(10);
        assessment.setCurrentStepKey("RESULT");
        assessment.setCompletedAt(CREATED_AT.plusHours(2));
        assessment.setSubmittedAt(CREATED_AT.plusHours(1));
        assessment.setLastSubmitVersion(7);

        AssessmentResponse response = service.submit(userId, assessmentId, new SubmitAssessmentRequest(7));

        assertEquals(10, response.version());
        assertEquals(AssessmentStatus.COMPLETED, response.status());
        verify(assessmentMapper, never()).update(isNull(), any());
        verifyNoInteractions(imageClient);
    }

    @Test
    void submittingTheFreshResponseVersionWithoutOtherEditsIsAlsoANoOp() {
        assessment.setVersion(8);
        assessment.setSubmittedAt(CREATED_AT);
        assessment.setLastSubmitVersion(7);
        assessment.setCurrentStepKey("AGENT");

        AssessmentResponse response = service.submit(userId, assessmentId, new SubmitAssessmentRequest(8));

        assertEquals(8, response.version());
        assertEquals(CREATED_AT, assessment.getSubmittedAt());
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void unrelatedStaleVersionAndCancelledSubmissionAreRejected() {
        assertStatus(HttpStatus.CONFLICT,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(6)));
        assessment.setStatus("CANCELLED");
        assertStatus(HttpStatus.CONFLICT,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(7)));
        verify(assessmentMapper, never()).update(isNull(), any());
        verifyNoInteractions(imageClient);
    }

    @Test
    void missingNecessaryBasicsAndNoLocationOrPictureAreNotSubmittable() {
        assessment.setTriggerContext(" ");
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(7)));
        assessment.setTriggerContext("抓小点时明显");
        withoutLocations();
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(7)));
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void picturesAloneAreAValidLocationInputAndSymptomsRemainOptional() {
        withoutLocations();
        AssessmentVisual visual = AssessmentVisual.create(assessmentId, UUID.randomUUID(), null,
                org.javaup.history.api.dto.HistoryEnums.VisualSourceType.USER_UPLOAD, 0, CREATED_AT);
        when(visualMapper.selectList(any())).thenReturn(List.of(visual));
        when(visualMapper.exists(any())).thenReturn(true);
        when(visualMapper.selectCount(any())).thenReturn(1L);

        AssessmentResponse response = service.submit(userId, assessmentId, new SubmitAssessmentRequest(7));

        assertEquals(8, response.version());
        assertTrue(response.symptomLocations().isEmpty());
        assertEquals(1, response.visualReferences().size());
        assertNull(response.initialInput().symptomTypes());
        verifyNoInteractions(imageClient);
    }

    @Test
    void relativeOnsetRequiresAmountAndExactDateCannotBeInTheFuture() {
        assessment.setOnsetKind("DAYS_AGO");
        assessment.setOnsetAmount(null);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(7)));
        assessment.setOnsetKind("EXACT_DATE");
        assessment.setOnsetDate(LocalDate.now(ZoneOffset.UTC).plusDays(1));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(7)));
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void submissionCasFailureDoesNotGenerateAResult() {
        when(assessmentMapper.update(isNull(), any())).thenReturn(0);

        assertStatus(HttpStatus.CONFLICT,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(7)));

        verify(resultMapper, never()).insert(any(AssessmentResult.class));
        verifyNoInteractions(imageClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COLLECTING", "CLARIFYING"})
    void submittedAssessmentAllowsAnswerSequenceGapsAndPreservesItsStatus(String status) {
        assessment.setStatus(status);
        withSubmissionReceipt();
        AppendAssessmentAnswerRequest request = answerRequest(UUID.randomUUID(), 5, "{\"value\":2}");

        AssessmentAnswerResponse response = service.appendAnswer(userId, assessmentId, request);

        assertEquals(5, response.sequenceNumber());
        assertEquals(request.clientAnswerId(), response.clientAnswerId());
        assertEquals(request.answer(), response.answer());
        ArgumentCaptor<AssessmentAnswer> inserted = ArgumentCaptor.forClass(AssessmentAnswer.class);
        verify(answerMapper).insertIfAbsent(inserted.capture());
        assertEquals(assessmentId, inserted.getValue().getAssessmentId());
        assertEquals("jsonb", inserted.getValue().getAnswer().getType());
        assertEquals(8, assessment.getVersion());
        assertEquals(status, assessment.getStatus());
        assertEquals(CREATED_AT, assessment.getSubmittedAt());
        assertEquals(6, assessment.getLastSubmitVersion());
        assertNotNull(assessment.getLastSavedAt());
        verifyNoInteractions(imageClient);
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT, false, false",
            "DRAFT, true, true",
            "COLLECTING, false, false",
            "COLLECTING, true, false",
            "COLLECTING, false, true"
    })
    void newAnswersRequireSubmissionAndACompleteReceipt(
            String status, boolean hasSubmittedAt, boolean hasLastSubmitVersion) {
        assessment.setStatus(status);
        assessment.setSubmittedAt(hasSubmittedAt ? CREATED_AT : null);
        assessment.setLastSubmitVersion(hasLastSubmitVersion ? 6 : null);

        ApiException exception = assertThrows(ApiException.class, () -> service.appendAnswer(userId, assessmentId,
                answerRequest(UUID.randomUUID(), 5, "{\"value\":2}")));

        assertEquals(HttpStatus.CONFLICT.value(), exception.status().value());
        assertEquals("ASSESSMENT_NOT_SUBMITTED", exception.code());
        assertEquals(status, assessment.getStatus());
        assertEquals(7, assessment.getVersion());
        assertEquals(CREATED_AT, assessment.getLastSavedAt());
        verify(answerMapper, never()).insertIfAbsent(any());
        verify(assessmentMapper, never()).update(isNull(), any());
        verifyNoInteractions(imageClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "COLLECTING", "COMPLETED", "CANCELLED"})
    void sameAnswerKeyAndStructurallyEqualJsonReplayWithoutReceiptEvenInTerminalStates(String status) {
        UUID clientId = UUID.randomUUID();
        AppendAssessmentAnswerRequest request = answerRequest(clientId, 5, "{\"value\":2,\"note\":\"不适\"}");
        AssessmentAnswer stored = storedAnswer(request, "{\"note\":\"不适\",\"value\":2}");
        assessment.setStatus(status);
        when(answerMapper.selectOne(any())).thenReturn(stored);

        AssessmentAnswerResponse response = service.appendAnswer(userId, assessmentId, request);

        assertEquals(stored.getId(), response.answerId());
        assertEquals(CREATED_AT.toInstant(), response.createdAt());
        assertNull(assessment.getSubmittedAt());
        assertNull(assessment.getLastSubmitVersion());
        verify(answerMapper, never()).insertIfAbsent(any());
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void reusingAnAnswerKeyForDifferentContentReturnsConflict() {
        withSubmissionReceipt();
        UUID clientId = UUID.randomUUID();
        AppendAssessmentAnswerRequest request = answerRequest(clientId, 5, "{\"value\":2}");
        when(answerMapper.selectOne(any())).thenReturn(storedAnswer(request, "{\"value\":1}"));

        assertStatus(HttpStatus.CONFLICT, () -> service.appendAnswer(userId, assessmentId, request));

        verify(answerMapper, never()).insertIfAbsent(any());
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void newAnswersCannotBeAddedToTerminalAssessmentsAndJsonNullIsRejected() {
        withSubmissionReceipt();
        assessment.setStatus("CANCELLED");
        assertStatus(HttpStatus.CONFLICT, () -> service.appendAnswer(userId, assessmentId,
                answerRequest(UUID.randomUUID(), 2, "{\"value\":2}")));
        assessment.setStatus("COLLECTING");
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.appendAnswer(userId, assessmentId,
                answerRequest(UUID.randomUUID(), 2, "null")));
        verify(answerMapper, never()).insertIfAbsent(any());
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void anOccupiedSequenceCannotBeReusedByAnotherAnswerKey() {
        withSubmissionReceipt();
        when(answerMapper.exists(any())).thenReturn(true);
        when(answerMapper.selectCount(any())).thenReturn(1L);

        assertStatus(HttpStatus.CONFLICT, () -> service.appendAnswer(userId, assessmentId,
                answerRequest(UUID.randomUUID(), 5, "{\"value\":2}")));

        verify(answerMapper, never()).insertIfAbsent(any());
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void insertDoNothingReloadsTheMatchingAnswerInsteadOfTouchingTheRootAgain() {
        withSubmissionReceipt();
        AppendAssessmentAnswerRequest request = answerRequest(UUID.randomUUID(), 9, "{\"value\":2}");
        AssessmentAnswer existing = storedAnswer(request, request.answer().toString());
        when(answerMapper.selectOne(any())).thenReturn(null, existing);
        when(answerMapper.insertIfAbsent(any())).thenReturn(0);

        AssessmentAnswerResponse response = service.appendAnswer(userId, assessmentId, request);

        assertEquals(existing.getId(), response.answerId());
        verify(assessmentMapper, never()).update(isNull(), any());
    }

    @Test
    void lifecycleReadSnapshotsAndWritesHaveExplicitTransactionBoundaries() throws Exception {
        for (String method : List.of("resume", "getResult")) {
            Transactional transaction = AssessmentServiceImpl.class
                    .getMethod(method, UUID.class, UUID.class).getAnnotation(Transactional.class);
            assertNotNull(transaction);
            assertTrue(transaction.readOnly());
            assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
        }
        for (Class<?> requestType : List.of(SubmitAssessmentRequest.class, AppendAssessmentAnswerRequest.class)) {
            String method = requestType == SubmitAssessmentRequest.class ? "submit" : "appendAnswer";
            Transactional transaction = AssessmentServiceImpl.class
                    .getMethod(method, UUID.class, UUID.class, requestType).getAnnotation(Transactional.class);
            assertNotNull(transaction);
            assertFalse(transaction.readOnly());
        }
    }

    @Test
    void clearingLocationsAfterSubmissionAdvancesVersionAndRevalidatesRequiredLocation() {
        assessment.setSubmittedAt(CREATED_AT);
        assessment.setLastSubmitVersion(6);
        when(locationService.lambdaUpdate()).thenAnswer(invocation ->
                new LambdaUpdateChainWrapper<>(locationMapper));
        when(assessmentMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<Assessment> update = invocation.getArgument(1);
            assertTrue(update.getSqlSet().replaceAll("\\s+", "").contains("version=version+1"));
            assertTrue(update.getSqlSegment().contains("version <"));
            assertTrue(update.getParamNameValuePairs().containsValue(Integer.MAX_VALUE));
            // Model the atomic SQL expression; this is not a real PostgreSQL test.
            assessment.setVersion(assessment.getVersion() + 1);
            return 1;
        });

        service.clearLocations(userId, assessmentId);
        assertEquals(8, assessment.getVersion());
        withoutLocations();

        ApiException exception = assertThrows(ApiException.class,
                () -> service.submit(userId, assessmentId, new SubmitAssessmentRequest(8)));
        assertEquals(422, exception.status().value());
        assertEquals("ASSESSMENT_LOCATION_REQUIRED", exception.code());
        assertEquals(6, assessment.getLastSubmitVersion());
    }

    @Test
    void replacingLocationsAfterSubmissionAdvancesVersionAndAllowsFreshSubmission() {
        assessment.setSubmittedAt(CREATED_AT);
        assessment.setLastSubmitVersion(6);
        when(locationService.lambdaUpdate()).thenAnswer(invocation ->
                new LambdaUpdateChainWrapper<>(locationMapper));
        when(locationService.saveBatch(anyCollection())).thenReturn(true);
        when(assessmentMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<Assessment> update = invocation.getArgument(1);
            if (update.getSqlSet().replaceAll("\\s+", "").contains("version=version+1")) {
                assertTrue(update.getSqlSegment().contains("version <"));
                assessment.setVersion(assessment.getVersion() + 1);
            }
            return 1;
        });
        var input = new SymptomLocationInput(HandSide.RIGHT, "hand.right.middle.pip",
                "右手中指 PIP 附近", FingerCode.MIDDLE, JointCode.PIP, null);

        service.replaceLocations(userId, assessmentId, new ReplaceSymptomLocationsRequest(List.of(input)));
        assertEquals(8, assessment.getVersion());
        AssessmentResponse response = service.submit(userId, assessmentId, new SubmitAssessmentRequest(8));

        assertEquals(9, response.version());
        assertEquals(8, assessment.getLastSubmitVersion());
        assertNull(response.completedAt());
    }

    @Test
    void exhaustedLocationVersionCannotMutateChildRows() {
        assessment.setVersion(Integer.MAX_VALUE);
        when(assessmentMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<Assessment> update = invocation.getArgument(1);
            assertTrue(update.getSqlSegment().contains("version <"));
            assertTrue(update.getParamNameValuePairs().containsValue(Integer.MAX_VALUE));
            return 0;
        });

        assertStatus(HttpStatus.CONFLICT, () -> service.clearLocations(userId, assessmentId));

        verify(locationService, never()).lambdaUpdate();
        verify(locationService, never()).saveBatch(anyCollection());
    }

    private Assessment assessment() {
        Assessment entity = new Assessment();
        entity.setId(assessmentId);
        entity.setUserId(userId);
        entity.setCreateIdempotencyKey(UUID.randomUUID());
        entity.setStatus("COLLECTING");
        entity.setCurrentStepKey("BASIC_INPUT");
        entity.setHandSide("RIGHT");
        entity.setOnsetKind("TODAY");
        entity.setTriggerContext("抓小点时明显");
        entity.setNormalUseImpact("MILD");
        entity.setVersion(7);
        entity.setCreatedAt(CREATED_AT);
        entity.setUpdatedAt(CREATED_AT);
        entity.setLastSavedAt(CREATED_AT);
        return entity;
    }

    private SymptomLocation location() {
        SymptomLocation location = new SymptomLocation();
        location.setId(UUID.randomUUID());
        location.setAssessmentId(assessmentId);
        location.setHandSide("RIGHT");
        location.setRegionId("hand.right.middle.pip");
        location.setRegionLabel("右手中指 PIP 附近");
        location.setPrimary(true);
        location.setCreatedAt(CREATED_AT);
        location.setUpdatedAt(CREATED_AT);
        return location;
    }

    private AssessmentResult result() {
        AssessmentResult result = new AssessmentResult();
        result.setAssessmentId(assessmentId);
        result.setRecommendationLevel("ROUTINE_PROFESSIONAL");
        result.setSummary("用于测试的症状描述摘要");
        result.setReasons(jsonb("[\"位置描述有待进一步确认\"]"));
        result.setSuggestedNextActions(jsonb("[\"携带本次记录咨询专业人员\"]"));
        result.setWarningSigns(jsonb("[]"));
        result.setGeneratedBy("MOCK");
        result.setSafetyNotice("仅用于整理不适描述，不提供诊断。");
        result.setFinalizedAt(CREATED_AT.plusHours(1));
        return result;
    }

    private AppendAssessmentAnswerRequest answerRequest(UUID clientId, int sequence, String json) {
        return new AppendAssessmentAnswerRequest(clientId, sequence, "symptom.quality", "当前是什么感觉？",
                objectMapper.readTree(json), AssessmentAnswerSource.MOCK_AGENT);
    }

    private AssessmentAnswer storedAnswer(AppendAssessmentAnswerRequest request, String json) {
        AssessmentAnswer answer = new AssessmentAnswer();
        answer.setId(UUID.randomUUID());
        answer.setAssessmentId(assessmentId);
        answer.setClientAnswerId(request.clientAnswerId());
        answer.setSequenceNo(request.sequenceNumber());
        answer.setQuestionKey(request.questionKey());
        answer.setQuestionText(request.questionText());
        answer.setAnswer(jsonb(json));
        answer.setAnswerSource(request.source().name());
        answer.setCreatedAt(CREATED_AT);
        return answer;
    }

    private void withoutLocations() {
        when(locationMapper.selectList(any())).thenReturn(List.of());
        when(locationMapper.exists(any())).thenReturn(false);
        when(locationMapper.selectCount(any())).thenReturn(0L);
    }

    private void withSubmissionReceipt() {
        assessment.setSubmittedAt(CREATED_AT);
        assessment.setLastSubmitVersion(6);
    }

    private void assertOwnedScope(AbstractWrapper<?, ?, ?> query) {
        String sql = query.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("user_id"));
        assertTrue(sql.contains("id"));
        assertEquals(Set.of(userId, assessmentId), new HashSet<>(query.getParamNameValuePairs().values()));
    }

    private PGobject jsonb(String json) {
        PGobject value = new PGobject();
        value.setType("jsonb");
        try {
            value.setValue(json);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
        return value;
    }

    private void assertStatus(HttpStatus expected, Runnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(expected.value(), exception.status().value());
    }
}
