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
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.javaup.history.api.dto.HistoryEnums.AssessmentStatus;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;
import org.javaup.history.api.dto.ReplaceVisualReferencesRequest;
import org.javaup.history.api.dto.VisualReference;
import org.javaup.history.api.problem.ApiException;
import org.javaup.history.client.ImageClient;
import org.javaup.history.entity.Assessment;
import org.javaup.history.entity.AssessmentVisual;
import org.javaup.history.mapper.AssessmentMapper;
import org.javaup.history.mapper.AssessmentAnswerMapper;
import org.javaup.history.service.AssessmentAnswerService;
import org.javaup.history.service.AssessmentResultService;
import org.javaup.history.service.AssessmentSymptomService;
import org.javaup.history.service.AssessmentVisualService;
import org.javaup.history.service.SymptomLocationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Isolated service decisions, MyBatis-Plus predicates and transaction boundaries.
 * A mocked transaction manager proves commit/rollback signals, not PostgreSQL rollback.
 */
class AssessmentVisualServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final UUID pictureId = UUID.randomUUID();
    private final AtomicBoolean localWriteActive = new AtomicBoolean();

    private AssessmentMapper assessmentMapper;
    private AssessmentVisualService assessmentVisualService;
    private LambdaUpdateChainWrapper<AssessmentVisual> visualDelete;
    private ImageClient imageClient;
    private PlatformTransactionManager transactionManager;
    private TransactionStatus transactionStatus;
    private AssessmentServiceImpl service;
    private Assessment assessment;

    @BeforeAll
    static void initializeMybatisPlusLambdaMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(Assessment.class, AssessmentVisual.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "visual-unit-test");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assessmentMapper = mock(AssessmentMapper.class);
        assessmentVisualService = mock(AssessmentVisualService.class);
        visualDelete = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        imageClient = mock(ImageClient.class);
        transactionManager = mock(PlatformTransactionManager.class);
        transactionStatus = mock(TransactionStatus.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
            assertFalse(localWriteActive.get());
            localWriteActive.set(true);
            return transactionStatus;
        });
        doAnswer(invocation -> {
            assertTrue(localWriteActive.get());
            localWriteActive.set(false);
            return null;
        }).when(transactionManager).commit(transactionStatus);
        doAnswer(invocation -> {
            assertTrue(localWriteActive.get());
            localWriteActive.set(false);
            return null;
        }).when(transactionManager).rollback(transactionStatus);

        service = new AssessmentServiceImpl(
                mock(AssessmentAnswerService.class),
                mock(AssessmentResultService.class),
                mock(AssessmentSymptomService.class),
                assessmentVisualService,
                mock(SymptomLocationService.class),
                assessmentMapper,
                JsonMapper.builder().build(),
                imageClient,
                transaction,
                mock(AssessmentAnswerMapper.class)
        );
        ReflectionTestUtils.setField(service, "baseMapper", assessmentMapper);
        // MP 3.5.17 normally derives this cache from the real MapperProxy.
        ReflectionTestUtils.setField(service, "entityClass", Assessment.class);

        assessment = new Assessment();
        assessment.setId(assessmentId);
        assessment.setUserId(userId);
        assessment.setStatus(AssessmentStatus.COLLECTING.name());
        assessment.setVersion(7);
        when(assessmentMapper.selectOne(any())).thenReturn(assessment);
        when(assessmentMapper.update(isNull(), any())).thenReturn(1);
        when(assessmentVisualService.lambdaUpdate()).thenReturn(visualDelete);
        // MP's generic default-method return erasure is not covered by RETURNS_SELF.
        when(visualDelete.eq(any(), any())).thenReturn(visualDelete);
        when(visualDelete.remove()).thenReturn(true);
        when(assessmentVisualService.saveBatch(anyCollection())).thenReturn(true);
    }

    @Test
    void assessmentLookupIsScopedToBothUserAndAssessmentAndMissingRecordStopsRemoteCalls() {
        when(assessmentMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            assertTrue(query.getSqlSegment().contains("user_id"));
            assertTrue(query.getSqlSegment().contains("id"));
            assertFalse(query.getSqlSegment().toLowerCase().contains(" or "));
            assertEquals(Set.of(userId, assessmentId),
                    new HashSet<>(query.getParamNameValuePairs().values()));
            return null;
        });

        assertFailure(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND",
                () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));

        verifyNoInteractions(imageClient, transactionManager, assessmentVisualService);
    }

    @Test
    void completedOrCancelledAssessmentCannotChangeVisuals() {
        for (AssessmentStatus status : List.of(AssessmentStatus.COMPLETED, AssessmentStatus.CANCELLED)) {
            assessment.setStatus(status.name());
            assertFailure(HttpStatus.CONFLICT, "ASSESSMENT_NOT_EDITABLE",
                    () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));
        }

        verifyNoInteractions(imageClient, transactionManager, assessmentVisualService);
    }

    @Test
    void staleVersionStopsBeforeAnyRemoteCallOrWrite() {
        assertFailure(HttpStatus.CONFLICT, "ASSESSMENT_VERSION_CONFLICT",
                () -> service.replaceVisuals(userId, assessmentId,
                        new ReplaceVisualReferencesRequest(List.of(pictureId), 6)));

        verifyNoInteractions(imageClient, transactionManager, assessmentVisualService);
    }

    @Test
    void invalidBodyAndNullOrDuplicatePictureIdsAreRejectedWithoutRemoteCalls() {
        List<ReplaceVisualReferencesRequest> invalid = Arrays.asList(
                null,
                new ReplaceVisualReferencesRequest(null, 7),
                new ReplaceVisualReferencesRequest(List.of(), null),
                new ReplaceVisualReferencesRequest(List.of(), 0),
                new ReplaceVisualReferencesRequest(List.of(), -1),
                new ReplaceVisualReferencesRequest(IntStream.range(0, 13)
                        .mapToObj(index -> UUID.randomUUID()).toList(), 7),
                new ReplaceVisualReferencesRequest(Arrays.asList(pictureId, null), 7),
                new ReplaceVisualReferencesRequest(List.of(pictureId, pictureId), 7)
        );

        for (ReplaceVisualReferencesRequest request : invalid) {
            ApiException exception = assertThrows(ApiException.class,
                    () -> service.replaceVisuals(userId, assessmentId, request));
            assertEquals(HttpStatus.BAD_REQUEST.value(), exception.status().value());
        }

        verifyNoInteractions(imageClient, transactionManager, assessmentVisualService);
    }

    @Test
    void successValidatesAllImagesOutsideTransactionAndThenCasDeletesAndInsertsInOrder() {
        UUID snapshotId = UUID.randomUUID();
        UUID standardId = UUID.randomUUID();
        UUID annotationId = UUID.randomUUID();
        UUID standardAnnotationId = UUID.randomUUID();
        List<UUID> ids = List.of(pictureId, snapshotId, standardId);

        when(imageClient.resolveVisualReferences(userId, resolveRequest(ids))).thenAnswer(invocation -> {
            assertFalse(localWriteActive.get());
            verifyNoInteractions(transactionManager);
            // Deliberately return a different order; History must restore the caller's order.
            return List.of(
                    resolved(standardId, VisualSourceType.STANDARD_REFERENCE, standardAnnotationId),
                    resolved(pictureId, VisualSourceType.USER_UPLOAD, annotationId),
                    resolved(snapshotId, VisualSourceType.MODEL_SNAPSHOT, null)
            );
        });
        when(assessmentMapper.update(isNull(), any())).thenAnswer(invocation -> {
            assertTrue(localWriteActive.get());
            LambdaUpdateWrapper<Assessment> update = invocation.getArgument(1);
            assertTrue(update.getSqlSegment().contains("user_id"));
            assertTrue(update.getSqlSegment().contains("version"));
            assertTrue(update.getSqlSegment().contains("status"));
            assertTrue(update.getSqlSet().contains("version="));
            assertTrue(update.getSqlSet().contains("last_saved_at="));
            assertTrue(update.getSqlSet().contains("updated_at="));
            assertTrue(update.getParamNameValuePairs().values()
                    .containsAll(List.of(assessmentId, userId, 7, 8, "COLLECTING")));
            return 1;
        });
        when(assessmentVisualService.saveBatch(anyCollection())).thenAnswer(invocation -> {
            assertTrue(localWriteActive.get());
            return true;
        });

        List<VisualReference> response = service.replaceVisuals(userId, assessmentId, request(ids));

        assertEquals(ids, response.stream().map(VisualReference::pictureId).toList());
        assertEquals(List.of(VisualSourceType.USER_UPLOAD, VisualSourceType.MODEL_SNAPSHOT,
                VisualSourceType.STANDARD_REFERENCE), response.stream().map(VisualReference::sourceType).toList());
        assertEquals(List.of(0, 1, 2), response.stream().map(VisualReference::sortOrder).toList());
        assertEquals(annotationId, response.getFirst().annotationSetId());
        assertNull(response.get(1).annotationSetId());
        assertEquals(standardAnnotationId, response.get(2).annotationSetId());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<java.util.Collection<AssessmentVisual>> captor = ArgumentCaptor.forClass((Class) java.util.Collection.class);
        verify(assessmentVisualService).saveBatch(captor.capture());
        List<AssessmentVisual> saved = new ArrayList<>(captor.getValue());
        assertEquals(ids, saved.stream().map(AssessmentVisual::getPictureId).toList());
        saved.forEach(row -> {
            assertNotNull(row.getId());
            assertEquals(assessmentId, row.getAssessmentId());
            assertNotNull(row.getCreatedAt());
        });
        verify(visualDelete).eq(any(), org.mockito.ArgumentMatchers.eq(assessmentId));
        InOrder order = inOrder(imageClient, transactionManager, assessmentMapper,
                visualDelete, assessmentVisualService);
        order.verify(imageClient).resolveVisualReferences(userId, resolveRequest(ids));
        order.verify(transactionManager).getTransaction(any());
        order.verify(assessmentMapper).update(isNull(), any());
        order.verify(visualDelete).remove();
        order.verify(assessmentVisualService).saveBatch(anyCollection());
        order.verify(transactionManager).commit(transactionStatus);
        assertFalse(localWriteActive.get());
        verify(transactionManager, never()).rollback(any());
        verifyNoMoreInteractions(imageClient);
    }

    @Test
    void emptyPictureListClearsAssociationsWithoutCallingImageOrInsertingRows() {
        List<VisualReference> response = service.replaceVisuals(userId, assessmentId, request(List.of()));

        assertTrue(response.isEmpty());
        verifyNoInteractions(imageClient);
        verify(visualDelete).remove();
        verify(assessmentVisualService, never()).saveBatch(anyCollection());
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void unavailableImageBatchIsRejectedBeforeWrites() {
        when(imageClient.resolveVisualReferences(userId, resolveRequest(List.of(pictureId))))
                .thenThrow(remoteFailure(409, "{\"code\":\"PICTURE_NOT_AVAILABLE\"}"));

        assertFailure(HttpStatus.CONFLICT, "PICTURE_NOT_AVAILABLE",
                () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));

        verifyNoInteractions(transactionManager, assessmentVisualService);
    }

    @Test
    void invalidRemoteBatchMetadataIsBadGatewayRatherThanFakeSuccess() {
        List<List<ImageClient.ResolvedVisualReference>> invalid = Arrays.asList(
                null,
                List.of(),
                Arrays.asList((ImageClient.ResolvedVisualReference) null),
                List.of(resolved(null, VisualSourceType.USER_UPLOAD, null)),
                List.of(resolved(UUID.randomUUID(), VisualSourceType.USER_UPLOAD, null)),
                List.of(resolved(pictureId, null, null)),
                List.of(resolved(pictureId, VisualSourceType.USER_UPLOAD, null),
                        resolved(pictureId, VisualSourceType.USER_UPLOAD, null))
        );
        for (List<ImageClient.ResolvedVisualReference> batch : invalid) {
            when(imageClient.resolveVisualReferences(userId, resolveRequest(List.of(pictureId))))
                    .thenReturn(batch);
            assertFailure(HttpStatus.BAD_GATEWAY, "INVALID_IMAGE_RESPONSE",
                    () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));
        }

        verifyNoInteractions(transactionManager, assessmentVisualService);
    }

    @Test
    void duplicateBatchIdsCannotHideMissingPictureEvenWhenCountMatches() {
        UUID secondId = UUID.randomUUID();
        List<UUID> requested = List.of(pictureId, secondId);
        when(imageClient.resolveVisualReferences(userId, resolveRequest(requested)))
                .thenReturn(List.of(
                        resolved(pictureId, VisualSourceType.USER_UPLOAD, null),
                        resolved(pictureId, VisualSourceType.USER_UPLOAD, null)));

        assertFailure(HttpStatus.BAD_GATEWAY, "INVALID_IMAGE_RESPONSE",
                () -> service.replaceVisuals(userId, assessmentId, request(requested)));

        verifyNoInteractions(transactionManager, assessmentVisualService);
    }

    @Test
    void remoteNotFoundConnectivityAndServerFailuresHaveDistinctResponsesAndNeverWrite() {
        when(imageClient.resolveVisualReferences(userId, resolveRequest(List.of(pictureId))))
                .thenThrow(remoteFailure(404, "{\"code\":\"PICTURE_NOT_FOUND\"}"));
        assertFailure(HttpStatus.NOT_FOUND, "IMAGE_REFERENCE_NOT_FOUND",
                () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));

        reset(imageClient);
        when(imageClient.resolveVisualReferences(userId, resolveRequest(List.of(pictureId))))
                .thenThrow(mock(RetryableException.class));
        assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_SERVICE_UNAVAILABLE",
                () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));

        reset(imageClient);
        when(imageClient.resolveVisualReferences(userId, resolveRequest(List.of(pictureId))))
                .thenThrow(remoteFailure(500, "{}"));
        assertFailure(HttpStatus.BAD_GATEWAY, "IMAGE_SERVICE_ERROR",
                () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));

        verifyNoInteractions(transactionManager, assessmentVisualService);
    }

    @Test
    void casFailureAfterRemoteValidationRollsBackWithoutDeletingOrInsertingAssociations() {
        validPictureWithNoAnnotation();
        when(assessmentMapper.update(isNull(), any())).thenReturn(0);

        assertFailure(HttpStatus.CONFLICT, "ASSESSMENT_VERSION_CONFLICT",
                () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));

        verifyNoInteractions(assessmentVisualService);
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        assertFalse(localWriteActive.get());
    }

    @Test
    void failedBatchInsertTriggersRollbackSignalInsteadOfReturningPartialSuccess() {
        validPictureWithNoAnnotation();
        when(assessmentVisualService.saveBatch(anyCollection())).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.replaceVisuals(userId, assessmentId, request(List.of(pictureId))));

        InOrder order = inOrder(assessmentMapper, visualDelete, assessmentVisualService, transactionManager);
        order.verify(assessmentMapper).update(isNull(), any());
        order.verify(visualDelete).remove();
        order.verify(assessmentVisualService).saveBatch(anyCollection());
        order.verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        assertFalse(localWriteActive.get());
    }

    private void validPictureWithNoAnnotation() {
        when(imageClient.resolveVisualReferences(userId, resolveRequest(List.of(pictureId))))
                .thenReturn(List.of(resolved(pictureId, VisualSourceType.USER_UPLOAD, null)));
    }

    private ReplaceVisualReferencesRequest request(List<UUID> ids) {
        return new ReplaceVisualReferencesRequest(ids, 7);
    }

    private ImageClient.ResolveVisualReferencesRequest resolveRequest(List<UUID> ids) {
        return new ImageClient.ResolveVisualReferencesRequest(assessmentId, ids);
    }

    private ImageClient.ResolvedVisualReference resolved(
            UUID id, VisualSourceType source, UUID annotationSetId
    ) {
        return new ImageClient.ResolvedVisualReference(id, source, annotationSetId);
    }

    private static FeignException remoteFailure(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST,
                "http://image-service/internal/image/visual-references/resolve", Map.of(), (byte[]) null,
                StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("ImageClient#resolveVisualReferences", Response.builder()
                .request(request)
                .status(status)
                .reason("test remote failure")
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build());
    }

    private void assertFailure(HttpStatus status, String code, Runnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(status.value(), exception.status().value());
        assertEquals(code, exception.code());
    }
}
