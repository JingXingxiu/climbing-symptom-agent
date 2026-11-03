package org.javaup.image.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.javaup.image.api.dto.ResolveVisualReferencesRequest;
import org.javaup.image.api.dto.ResolvedVisualReference;
import org.javaup.image.api.dto.VisualSourceType;
import org.javaup.image.api.problem.ApiException;
import org.javaup.image.config.MinioProperties;
import org.javaup.image.entity.AnnotationSet;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests assert exact mapper calls, not a simulated database timing result. */
class PictureVisualReferencesServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();

    private PictureAssetMapper pictureMapper;
    private AnnotationSetMapper annotationSetMapper;
    private PictureAnnotationMapper annotationMapper;
    private PictureVariantService variantService;
    private MinioObjectUrlSigner signer;
    private MinioObjectStorage storage;
    private PictureAssetServiceImpl service;

    @BeforeAll
    static void initializeLambdaMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(PictureAsset.class, AnnotationSet.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "visual-batch-test");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        pictureMapper = mock(PictureAssetMapper.class);
        annotationSetMapper = mock(AnnotationSetMapper.class);
        annotationMapper = mock(PictureAnnotationMapper.class);
        variantService = mock(PictureVariantService.class);
        signer = mock(MinioObjectUrlSigner.class);
        storage = mock(MinioObjectStorage.class);
        service = new PictureAssetServiceImpl(
                JsonMapper.builder().build(), variantService, signer, storage,
                new MinioProperties("http://localhost:9000", "access", "secret", "us-east-1", "pictures", 600),
                mock(TransactionTemplate.class), annotationSetMapper, annotationMapper
        );
        ReflectionTestUtils.setField(service, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(service, "entityClass", PictureAsset.class);
    }

    @Test
    void resolvesTwelvePicturesWithExactlyTwoSelectsAndPreservesRequestOrder() {
        List<UUID> ids = IntStream.range(0, 12).mapToObj(index -> UUID.randomUUID()).toList();
        List<PictureAsset> unorderedPictures = new ArrayList<>();
        for (int index = ids.size() - 1; index >= 0; index--) {
            unorderedPictures.add(picture(ids.get(index), index % 2 == 0
                    ? VisualSourceType.USER_UPLOAD : VisualSourceType.STANDARD_REFERENCE, "AVAILABLE"));
        }
        AnnotationSet annotationSet = annotationSet(ids.get(5));
        when(pictureMapper.selectList(any())).thenReturn(unorderedPictures);
        when(annotationSetMapper.selectList(any())).thenReturn(List.of(annotationSet));

        List<ResolvedVisualReference> result = service.resolveVisualReferences(
                userId, new ResolveVisualReferencesRequest(assessmentId, ids));

        assertEquals(ids, result.stream().map(ResolvedVisualReference::pictureId).toList());
        assertEquals(VisualSourceType.STANDARD_REFERENCE, result.get(5).sourceType());
        assertEquals(annotationSet.getId(), result.get(5).annotationSetId());
        assertEquals(1, result.stream().filter(reference -> reference.annotationSetId() != null).count());
        verify(pictureMapper, times(1)).selectList(any());
        verify(annotationSetMapper, times(1)).selectList(any());
        verifyNoMoreInteractions(pictureMapper, annotationSetMapper);
        verifyNoInteractions(annotationMapper, variantService, signer, storage);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void selectsOnlyMinimalColumnsAndScopesBothQueriesCorrectly() {
        UUID id = UUID.randomUUID();
        when(pictureMapper.selectList(any())).thenReturn(List.of(picture(id, VisualSourceType.MODEL_SNAPSHOT, "AVAILABLE")));
        when(annotationSetMapper.selectList(any())).thenReturn(List.of());

        service.resolveVisualReferences(userId, new ResolveVisualReferencesRequest(assessmentId, List.of(id)));

        ArgumentCaptor<LambdaQueryWrapper<PictureAsset>> pictures = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(pictureMapper).selectList(pictures.capture());
        LambdaQueryWrapper<PictureAsset> pictureQuery = pictures.getValue();
        String pictureSql = pictureQuery.getSqlSegment().toLowerCase();
        assertTrue(pictureSql.contains("id in"));
        assertTrue(pictureSql.contains("deleted_at is null"));
        assertTrue(pictureSql.contains("and (source_type ="));
        assertTrue(pictureSql.contains("or owner_user_id ="));
        assertEquals("id,source_type,storage_status", pictureQuery.getSqlSelect());
        assertEquals(new HashSet<>(List.of(id, "STANDARD_REFERENCE", userId)),
                new HashSet<>(pictureQuery.getParamNameValuePairs().values()));

        ArgumentCaptor<LambdaQueryWrapper<AnnotationSet>> sets = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(annotationSetMapper).selectList(sets.capture());
        LambdaQueryWrapper<AnnotationSet> annotationQuery = sets.getValue();
        String annotationSql = annotationQuery.getSqlSegment().toLowerCase();
        assertTrue(annotationSql.contains("picture_id in"));
        assertTrue(annotationSql.contains("owner_user_id ="));
        assertTrue(annotationSql.contains("assessment_id ="));
        assertEquals("id,picture_id", annotationQuery.getSqlSelect());
        assertEquals(new HashSet<>(List.of(id, userId, assessmentId)),
                new HashSet<>(annotationQuery.getParamNameValuePairs().values()));
    }

    @Test
    void emptyListNeedsNoDatabaseOrObjectStorageCalls() {
        assertEquals(List.of(), service.resolveVisualReferences(
                userId, new ResolveVisualReferencesRequest(assessmentId, List.of())));
        verifyNoInteractions(pictureMapper, annotationSetMapper, annotationMapper, variantService, signer, storage);
    }

    @Test
    void missingDeletedOrForeignPictureFailsTheWholeBatchBeforeAnnotationLookup() {
        UUID accessible = UUID.randomUUID();
        UUID inaccessible = UUID.randomUUID();
        // The database access predicate excludes missing, deleted and foreign rows alike.
        when(pictureMapper.selectList(any())).thenReturn(List.of(picture(accessible, VisualSourceType.USER_UPLOAD, "AVAILABLE")));

        ApiException failure = assertThrows(ApiException.class, () -> service.resolveVisualReferences(
                userId, new ResolveVisualReferencesRequest(assessmentId, List.of(accessible, inaccessible))));

        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        assertEquals("PICTURE_NOT_FOUND", failure.code());
        verify(pictureMapper, times(1)).selectList(any());
        verifyNoInteractions(annotationSetMapper, annotationMapper, variantService, signer, storage);
    }

    @Test
    void unavailablePictureFailsTheWholeBatchBeforeAnnotationLookup() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(pictureMapper.selectList(any())).thenReturn(List.of(
                picture(first, VisualSourceType.USER_UPLOAD, "AVAILABLE"),
                picture(second, VisualSourceType.MODEL_SNAPSHOT, "INITIATED")));

        ApiException failure = assertThrows(ApiException.class, () -> service.resolveVisualReferences(
                userId, new ResolveVisualReferencesRequest(assessmentId, List.of(first, second))));

        assertEquals(HttpStatus.CONFLICT, failure.status());
        assertEquals("PICTURE_NOT_AVAILABLE", failure.code());
        verify(pictureMapper, times(1)).selectList(any());
        verifyNoInteractions(annotationSetMapper, annotationMapper, variantService, signer, storage);
    }

    @Test
    void rejectsInvalidListsBeforeAnyQueries() {
        UUID id = UUID.randomUUID();
        List<ResolveVisualReferencesRequest> invalid = Arrays.asList(
                null,
                new ResolveVisualReferencesRequest(null, List.of()),
                new ResolveVisualReferencesRequest(assessmentId, null),
                new ResolveVisualReferencesRequest(assessmentId, Arrays.asList(id, null)),
                new ResolveVisualReferencesRequest(assessmentId, List.of(id, id)),
                new ResolveVisualReferencesRequest(assessmentId, Collections.nCopies(13, id))
        );
        for (ResolveVisualReferencesRequest request : invalid) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> service.resolveVisualReferences(userId, request));
            assertEquals(HttpStatus.BAD_REQUEST, failure.status());
        }
        ApiException missingUser = assertThrows(ApiException.class, () -> service.resolveVisualReferences(
                null, new ResolveVisualReferencesRequest(assessmentId, List.of())));
        assertEquals(HttpStatus.BAD_REQUEST, missingUser.status());
        verifyNoInteractions(pictureMapper, annotationSetMapper, annotationMapper, variantService, signer, storage);
    }

    @Test
    void batchReadsUseOneRepeatableReadTransaction() throws Exception {
        Transactional transaction = PictureAssetServiceImpl.class.getMethod(
                "resolveVisualReferences", UUID.class, ResolveVisualReferencesRequest.class
        ).getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
    }

    private PictureAsset picture(UUID id, VisualSourceType source, String status) {
        PictureAsset picture = new PictureAsset();
        picture.setId(id);
        picture.setSourceType(source.name());
        picture.setStorageStatus(status);
        return picture;
    }

    private AnnotationSet annotationSet(UUID pictureId) {
        AnnotationSet annotationSet = new AnnotationSet();
        annotationSet.setId(UUID.randomUUID());
        annotationSet.setPictureId(pictureId);
        return annotationSet;
    }
}
