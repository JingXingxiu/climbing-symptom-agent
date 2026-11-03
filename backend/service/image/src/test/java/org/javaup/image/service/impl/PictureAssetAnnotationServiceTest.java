package org.javaup.image.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.javaup.image.api.dto.AnnotationGeometry;
import org.javaup.image.api.dto.AnnotationSetResponse;
import org.javaup.image.api.dto.AnnotationShape;
import org.javaup.image.api.dto.AnnotationShapeType;
import org.javaup.image.api.dto.NormalizedPoint;
import org.javaup.image.api.dto.ReplaceAnnotationSetRequest;
import org.javaup.image.api.problem.ApiException;
import org.javaup.image.config.MinioProperties;
import org.javaup.image.entity.AnnotationSet;
import org.javaup.image.entity.PictureAnnotation;
import org.javaup.image.entity.PictureAsset;
import org.javaup.image.mapper.AnnotationSetMapper;
import org.javaup.image.mapper.PictureAnnotationMapper;
import org.javaup.image.mapper.PictureAssetMapper;
import org.javaup.image.service.PictureVariantService;
import org.javaup.image.storage.MinioObjectStorage;
import org.javaup.image.storage.MinioObjectUrlSigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises annotation service decisions and generated MyBatis-Plus wrappers.
 * These are deliberately isolated unit tests: actual PostgreSQL uniqueness and
 * transaction rollback still require a database integration test.
 */
class PictureAssetAnnotationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T10:00:00Z");

    private final UUID ownerId = UUID.randomUUID();
    private final UUID pictureId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private PictureAssetMapper pictureAssetMapper;
    private AnnotationSetMapper annotationSetMapper;
    private PictureAnnotationMapper pictureAnnotationMapper;
    private PictureAssetServiceImpl service;
    private PictureAsset picture;

    @BeforeAll
    static void initializeMybatisPlusLambdaMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(PictureAsset.class, AnnotationSet.class, PictureAnnotation.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "annotation-unit-test");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        pictureAssetMapper = mock(PictureAssetMapper.class);
        annotationSetMapper = mock(AnnotationSetMapper.class);
        pictureAnnotationMapper = mock(PictureAnnotationMapper.class);
        service = new PictureAssetServiceImpl(
                objectMapper,
                mock(PictureVariantService.class),
                mock(MinioObjectUrlSigner.class),
                mock(MinioObjectStorage.class),
                new MinioProperties("http://localhost:9000", "access", "secret", "us-east-1", "pictures", 600),
                mock(TransactionTemplate.class),
                annotationSetMapper,
                pictureAnnotationMapper
        );
        ReflectionTestUtils.setField(service, "baseMapper", pictureAssetMapper);
        // MP 3.5.17 normally derives this cache from a real MyBatis MapperProxy.
        // Keep production lambda-wrapper generation while using a Mockito mapper.
        ReflectionTestUtils.setField(service, "entityClass", PictureAsset.class);
        picture = new PictureAsset();
        picture.setId(pictureId);
        picture.setOwnerUserId(ownerId);
        picture.setSourceType("USER_UPLOAD");
        picture.setStorageStatus("AVAILABLE");
        when(pictureAssetMapper.selectOne(any())).thenReturn(picture);
    }

    @Test
    void readsOnlyTheCurrentUsersAssessmentContextAndRestoresGeometryAndStyle() throws Exception {
        AnnotationSet existing = annotationSet(4);
        AnnotationShape shape = pointShape(UUID.randomUUID());
        PictureAnnotation stored = storedAnnotation(existing.getId(), shape, 0);
        when(annotationSetMapper.selectOne(any())).thenAnswer(invocation -> {
            assertScope(invocation.getArgument(0), ownerId, pictureId, assessmentId);
            return existing;
        });
        when(pictureAnnotationMapper.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
            String sql = wrapper.getSqlSegment().toLowerCase();
            assertTrue(sql.contains("annotation_set_id"));
            assertTrue(sql.contains("order by sort_order asc"));
            assertEquals(List.of(existing.getId()), new ArrayList<>(wrapper.getParamNameValuePairs().values()));
            return List.of(stored);
        });

        AnnotationSetResponse response = service.getPictureAnnotationSet(ownerId, pictureId, assessmentId);

        assertEquals(existing.getId(), response.annotationSetId());
        assertEquals(pictureId, response.pictureId());
        assertEquals(800, response.sourceWidth());
        assertEquals(600, response.sourceHeight());
        assertEquals(4, response.version());
        assertEquals(List.of(shape), response.shapes());
        assertEquals(NOW.toInstant(), response.updatedAt());
        verify(pictureAnnotationMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void usesRepeatableReadForTheTwoReadQueriesAndATransactionForReplacement() throws Exception {
        Transactional readTransaction = PictureAssetServiceImpl.class
                .getMethod("getPictureAnnotationSet", UUID.class, UUID.class, UUID.class)
                .getAnnotation(Transactional.class);
        assertNotNull(readTransaction);
        assertTrue(readTransaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, readTransaction.isolation());
        Transactional writeTransaction = PictureAssetServiceImpl.class
                .getMethod("replacePictureAnnotationSet", UUID.class, UUID.class, ReplaceAnnotationSetRequest.class)
                .getAnnotation(Transactional.class);
        assertNotNull(writeTransaction);
        assertFalse(writeTransaction.readOnly());
    }

    @Test
    void returnsNotFoundWhenNoAnnotationSetExistsInThisContext() {
        when(annotationSetMapper.selectOne(any())).thenReturn(null);

        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.getPictureAnnotationSet(ownerId, pictureId, assessmentId));

        verifyNoInteractions(pictureAnnotationMapper);
    }

    @Test
    void createsVersionOneAndPersistsShapesWithoutBurningThemIntoThePicture() {
        AnnotationShape first = pointShape(UUID.randomUUID());
        AnnotationShape second = new AnnotationShape(UUID.randomUUID(), AnnotationShapeType.CIRCLE,
                new AnnotationGeometry.Circle(new NormalizedPoint(0.5, 0.4), 0.1, 0.2), null, null, null);
        when(annotationSetMapper.insertIfAbsent(any())).thenReturn(1);
        when(pictureAnnotationMapper.insert(any(PictureAnnotation.class))).thenReturn(1);

        AnnotationSetResponse response = service.replacePictureAnnotationSet(ownerId, pictureId,
                request(0, List.of(first, second), "中指不适区域"));

        assertEquals(1, response.version());
        assertEquals(List.of(first, second), response.shapes());
        assertEquals("中指不适区域", response.note());
        ArgumentCaptor<AnnotationSet> setCaptor = ArgumentCaptor.forClass(AnnotationSet.class);
        verify(annotationSetMapper).insertIfAbsent(setCaptor.capture());
        AnnotationSet created = setCaptor.getValue();
        assertEquals(ownerId, created.getOwnerUserId());
        assertEquals(pictureId, created.getPictureId());
        assertEquals(assessmentId, created.getAssessmentId());
        assertEquals("DRAFT", created.getStatus());
        assertEquals("NORMALIZED_0_1", created.getCoordinateSpace());
        assertEquals(800, created.getSourceWidth());
        assertEquals(600, created.getSourceHeight());
        assertEquals(1, created.getSchemaVersion());
        ArgumentCaptor<PictureAnnotation> shapes = ArgumentCaptor.forClass(PictureAnnotation.class);
        verify(pictureAnnotationMapper, org.mockito.Mockito.times(2)).insert(shapes.capture());
        assertEquals(List.of(0, 1), shapes.getAllValues().stream().map(PictureAnnotation::getSortOrder).toList());
        assertTrue(shapes.getAllValues().stream().allMatch(row -> created.getId().equals(row.getAnnotationSetId())));
        assertEquals("point", objectMapper.readTree(shapes.getAllValues().getFirst().getGeometry().getValue())
                .get("kind").asText());
        assertEquals("{}", shapes.getAllValues().get(1).getStyle().getValue());
        verify(pictureAssetMapper, never()).update(any(PictureAsset.class), any());
    }

    @Test
    void concurrentFirstCreationReturnsConflictWithoutDeletingExistingShapes() {
        when(annotationSetMapper.insertIfAbsent(any())).thenReturn(0);

        assertStatus(HttpStatus.CONFLICT, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                request(0, List.of(pointShape(UUID.randomUUID())), null)));

        verifyNoInteractions(pictureAnnotationMapper);
    }

    @Test
    void staleRevisionReturnsConflictBeforeDeletingAnyShapes() {
        when(annotationSetMapper.selectOne(any())).thenReturn(annotationSet(3));
        when(annotationSetMapper.update(isNull(), any())).thenReturn(0);

        assertStatus(HttpStatus.CONFLICT, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                request(2, List.of(), null)));

        verify(pictureAnnotationMapper, never()).delete(any(Wrapper.class));
        verify(pictureAnnotationMapper, never()).insert(any(PictureAnnotation.class));
    }

    @Test
    void emptyShapesAndNullNoteReallyClearTheContentAndIncrementTheRevision() {
        AnnotationSet existing = annotationSet(3);
        existing.setNote("old note");
        when(annotationSetMapper.selectOne(any())).thenAnswer(invocation -> {
            assertScope(invocation.getArgument(0), ownerId, pictureId, assessmentId);
            return existing;
        });
        when(annotationSetMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<AnnotationSet> update = invocation.getArgument(1);
            assertTrue(update.getSqlSegment().contains("revision"));
            assertTrue(update.getSqlSegment().contains("owner_user_id"));
            assertTrue(update.getSqlSegment().contains("status"));
            assertTrue(update.getSqlSet().contains("note="));
            assertTrue(update.getParamNameValuePairs().containsValue(null));
            assertTrue(update.getParamNameValuePairs().containsValue(3));
            assertTrue(update.getParamNameValuePairs().containsValue(4));
            return 1;
        });
        when(pictureAnnotationMapper.delete(any(Wrapper.class))).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
            assertTrue(wrapper.getSqlSegment().contains("annotation_set_id"));
            assertEquals(List.of(existing.getId()), new ArrayList<>(wrapper.getParamNameValuePairs().values()));
            return 2;
        });

        AnnotationSetResponse response = service.replacePictureAnnotationSet(ownerId, pictureId,
                request(3, List.of(), null));

        assertEquals(4, response.version());
        assertNull(response.note());
        assertTrue(response.shapes().isEmpty());
        verify(pictureAnnotationMapper).delete(any(Wrapper.class));
        verify(pictureAnnotationMapper, never()).insert(any(PictureAnnotation.class));
    }

    @Test
    void duplicateShapeIdsAreRejectedBeforeAnyAnnotationWrite() {
        AnnotationShape shape = pointShape(UUID.randomUUID());

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                request(0, List.of(shape, shape), null)));

        verifyNoInteractions(annotationSetMapper, pictureAnnotationMapper);
    }

    @Test
    void inconsistentTypeAndGeometryAreRejectedBeforeAnyAnnotationWrite() {
        AnnotationShape mismatch = new AnnotationShape(UUID.randomUUID(), AnnotationShapeType.POLYGON,
                new AnnotationGeometry.Point(new NormalizedPoint(0.4, 0.5)), null, null, null);

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                request(0, List.of(mismatch), null)));

        verifyNoInteractions(annotationSetMapper, pictureAnnotationMapper);
    }

    @Test
    void rejectsNullShapesAndRequiredShapeFieldsInsteadOfThrowingNullPointerException() {
        List<AnnotationShape> invalidShapes = Arrays.asList(null,
                new AnnotationShape(null, AnnotationShapeType.POINT,
                        new AnnotationGeometry.Point(new NormalizedPoint(0.2, 0.3)), null, null, null),
                new AnnotationShape(UUID.randomUUID(), null,
                        new AnnotationGeometry.Point(new NormalizedPoint(0.2, 0.3)), null, null, null),
                new AnnotationShape(UUID.randomUUID(), AnnotationShapeType.POINT, null, null, null, null));

        for (AnnotationShape invalid : invalidShapes) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                    request(0, Arrays.asList(invalid), null)));
        }

        verifyNoInteractions(annotationSetMapper, pictureAnnotationMapper);
    }

    @Test
    void inaccessiblePictureReturnsNotFoundAndTheLookupIncludesTheAccessConditions() {
        when(pictureAssetMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
            String sql = wrapper.getSqlSegment().toLowerCase();
            assertTrue(sql.contains("deleted_at is null"));
            assertTrue(sql.contains("source_type"));
            assertTrue(sql.contains("or"));
            assertTrue(sql.contains("owner_user_id"));
            assertEquals(Set.of(pictureId, ownerId, "STANDARD_REFERENCE"),
                    new HashSet<>(wrapper.getParamNameValuePairs().values()));
            return null;
        });

        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.getPictureAnnotationSet(ownerId, pictureId, assessmentId));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                request(0, List.of(), null)));

        verifyNoInteractions(annotationSetMapper, pictureAnnotationMapper);
    }

    @Test
    void unavailablePictureCannotReceiveNewAnnotations() {
        picture.setStorageStatus("INITIATED");

        assertStatus(HttpStatus.CONFLICT, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                request(0, List.of(), null)));

        verifyNoInteractions(annotationSetMapper, pictureAnnotationMapper);
    }

    @Test
    void duplicateGlobalShapeIdReturnsConflictAndDoesNotContinueAfterTheFailedInsert() {
        when(annotationSetMapper.selectOne(any())).thenReturn(annotationSet(3));
        when(annotationSetMapper.update(isNull(), any())).thenReturn(1);
        when(pictureAnnotationMapper.insert(any(PictureAnnotation.class)))
                .thenThrow(new DuplicateKeyException("shape belongs to a different annotation set"));

        assertStatus(HttpStatus.CONFLICT, () -> service.replacePictureAnnotationSet(ownerId, pictureId,
                request(3, List.of(pointShape(UUID.randomUUID()), pointShape(UUID.randomUUID())), null)));

        verify(pictureAnnotationMapper).insert(any(PictureAnnotation.class));
        verify(pictureAnnotationMapper, never()).selectList(any());
        // The @Transactional proxy performs rollback in production, not this direct invocation.
    }

    private void assertScope(Wrapper<?> wrapper, UUID owner, UUID picture, UUID assessment) {
        AbstractWrapper<?, ?, ?> query = (AbstractWrapper<?, ?, ?>) wrapper;
        String sql = query.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("picture_id"));
        assertTrue(sql.contains("owner_user_id"));
        assertTrue(sql.contains("assessment_id"));
        assertFalse(sql.contains(" or "));
        assertEquals(Set.of(owner, picture, assessment), new HashSet<>(query.getParamNameValuePairs().values()));
    }

    private AnnotationSet annotationSet(int revision) {
        AnnotationSet set = new AnnotationSet();
        set.setId(UUID.randomUUID());
        set.setPictureId(pictureId);
        set.setOwnerUserId(ownerId);
        set.setAssessmentId(assessmentId);
        set.setStatus("DRAFT");
        set.setCoordinateSpace("NORMALIZED_0_1");
        set.setSchemaVersion(1);
        set.setRevision(revision);
        set.setSourceWidth(800);
        set.setSourceHeight(600);
        set.setCreatedAt(NOW);
        set.setUpdatedAt(NOW);
        return set;
    }

    private ReplaceAnnotationSetRequest request(int version, List<AnnotationShape> shapes, String note) {
        return new ReplaceAnnotationSetRequest(assessmentId, 800, 600, shapes, note, version);
    }

    private AnnotationShape pointShape(UUID id) {
        return new AnnotationShape(id, AnnotationShapeType.POINT,
                new AnnotationGeometry.Point(new NormalizedPoint(0.25, 0.75)), "#FF0000", 0.01, "不适位置");
    }

    private PictureAnnotation storedAnnotation(UUID setId, AnnotationShape shape, int order) throws SQLException {
        PictureAnnotation annotation = new PictureAnnotation();
        annotation.setId(shape.shapeId());
        annotation.setAnnotationSetId(setId);
        annotation.setShapeType(shape.type().name());
        annotation.setGeometry(jsonb(objectMapper.writerFor(AnnotationGeometry.class)
                .writeValueAsString(shape.geometry())));
        annotation.setStyle(jsonb("{\"color\":\"#FF0000\",\"lineWidthNormalized\":0.01}"));
        annotation.setLabel(shape.label());
        annotation.setSortOrder(order);
        annotation.setCreatedAt(NOW);
        annotation.setUpdatedAt(NOW);
        return annotation;
    }

    private PGobject jsonb(String value) throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(value);
        return json;
    }

    private void assertStatus(HttpStatus expected, Runnable operation) {
        ApiException exception = assertThrows(ApiException.class, operation::run);
        assertEquals(expected.value(), exception.status().value());
    }
}
