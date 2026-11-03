package org.javaup.image.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.javaup.image.api.dto.*;
import org.javaup.image.api.problem.ApiException;
import org.javaup.image.entity.AnnotationSet;
import org.javaup.image.entity.PictureAnnotation;
import org.javaup.image.entity.PictureAsset;
import org.javaup.image.mapper.AnnotationSetMapper;
import org.javaup.image.mapper.PictureAnnotationMapper;
import org.javaup.image.mapper.PictureAssetMapper;
import org.javaup.image.storage.MinioObjectUrlSigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class AgentPictureServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final JsonMapper json = JsonMapper.builder().build();
    private PictureAssetMapper pictures;
    private AnnotationSetMapper sets;
    private PictureAnnotationMapper shapes;
    private MinioObjectUrlSigner signer;
    private AgentPictureServiceImpl service;

    @BeforeAll
    static void initializeMetadata() {
        MybatisConfiguration config = new MybatisConfiguration();
        for (Class<?> entity : List.of(PictureAsset.class, AnnotationSet.class, PictureAnnotation.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(config, "agent-picture-test");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setup() {
        pictures = mock(PictureAssetMapper.class);
        sets = mock(AnnotationSetMapper.class);
        shapes = mock(PictureAnnotationMapper.class);
        signer = mock(MinioObjectUrlSigner.class);
        service = new AgentPictureServiceImpl(pictures, sets, shapes, signer, json);
    }

    @Test
    void freezesTwelvePicturesAndAnnotationGeometryWithThreeBatchSelects() throws Exception {
        List<UUID> ids = IntStream.range(0, 12).mapToObj(index -> UUID.randomUUID()).toList();
        List<PictureAsset> unordered = new ArrayList<>();
        for (int index = ids.size() - 1; index >= 0; index--) {
            PictureAsset picture = picture(ids.get(index));
            if (index == 1) {
                picture.setSourceType(VisualSourceType.STANDARD_REFERENCE.name());
                picture.setOwnerUserId(null);
            }
            unordered.add(picture);
        }
        when(pictures.selectList(any())).thenReturn(unordered);
        AnnotationSet set = annotationSet(ids.get(3));
        when(sets.selectList(any())).thenReturn(List.of(set));
        PictureAnnotation shape = PictureAnnotation.create(set.getId(), new AnnotationShape(
                UUID.randomUUID(), AnnotationShapeType.CIRCLE,
                new AnnotationGeometry.Circle(new NormalizedPoint(0.4, 0.5), 0.1, 0.15),
                "#ff0000", 0.01, "中指不适区域"), 0, OffsetDateTime.now(), json);
        when(shapes.selectList(any())).thenReturn(List.of(shape));
        when(signer.signGet(any(), any())).thenReturn(URI.create("https://pictures.example/original?signature=temporary"));

        List<ResolvedAgentPicture> response = service.resolve(userId,
                new ResolveAgentPicturesRequest(assessmentId, ids));

        assertEquals(ids, response.stream().map(ResolvedAgentPicture::pictureId).toList());
        assertEquals(12, response.size());
        assertNull(response.get(0).annotation());
        assertEquals(set.getId().toString(), response.get(3).annotation().get("annotationSetId").asText());
        assertEquals(2, response.get(3).annotation().get("version").asInt());
        assertEquals("circle", response.get(3).annotation().get("shapes").get(0).get("geometry").get("kind").asText());
        assertEquals(0.4, response.get(3).annotation().get("shapes").get(0).get("geometry").get("center").get("x").asDouble());
        assertEquals(1.2, response.get(0).viewState().get("zoom").asDouble());
        assertTrue(response.stream().allMatch(item -> item.originalUrl() != null));
        verify(pictures, times(1)).selectList(any());
        verify(sets, times(1)).selectList(any());
        verify(shapes, times(1)).selectList(any());
        verify(signer, times(12)).signGet(any(), any());
        verifyNoMoreInteractions(pictures, sets, shapes);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void scopesQueriesToPictureAccessAndAssessmentOwner() throws Exception {
        UUID id = UUID.randomUUID();
        when(pictures.selectList(any())).thenReturn(List.of(picture(id)));
        when(sets.selectList(any())).thenReturn(List.of());
        when(signer.signGet(any(), any())).thenReturn(URI.create("http://minio.local/picture"));
        service.resolve(userId, new ResolveAgentPicturesRequest(assessmentId, List.of(id)));

        ArgumentCaptor<LambdaQueryWrapper<PictureAsset>> pictureQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(pictures).selectList(pictureQuery.capture());
        String pictureSql = pictureQuery.getValue().getSqlSegment();
        assertTrue(pictureSql.contains("deleted_at IS NULL"));
        assertTrue(pictureSql.contains("AND (source_type ="));
        assertTrue(pictureSql.contains("OR owner_user_id ="));
        ArgumentCaptor<LambdaQueryWrapper<AnnotationSet>> setQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sets).selectList(setQuery.capture());
        assertTrue(setQuery.getValue().getSqlSegment().contains("assessment_id ="));
        assertTrue(setQuery.getValue().getSqlSegment().contains("owner_user_id ="));
        assertTrue(setQuery.getValue().getParamNameValuePairs().containsValue(assessmentId));
        assertTrue(setQuery.getValue().getParamNameValuePairs().containsValue(userId));
        verifyNoInteractions(shapes);
    }

    @Test
    void emptyBatchHasNoDatabaseOrSigningCalls() {
        assertEquals(List.of(), service.resolve(userId, new ResolveAgentPicturesRequest(assessmentId, List.of())));
        verifyNoInteractions(pictures, sets, shapes, signer);
    }

    @Test
    void rejectsInvalidBatchesBeforeReadingAnything() {
        UUID id = UUID.randomUUID();
        List<ResolveAgentPicturesRequest> invalid = Arrays.asList(null,
                new ResolveAgentPicturesRequest(null, List.of()),
                new ResolveAgentPicturesRequest(assessmentId, null),
                new ResolveAgentPicturesRequest(assessmentId, List.of(id, id)),
                new ResolveAgentPicturesRequest(assessmentId, Arrays.asList(id, null)),
                new ResolveAgentPicturesRequest(assessmentId, IntStream.range(0, 13).mapToObj(i -> UUID.randomUUID()).toList()));
        for (var request : invalid) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                    () -> service.resolve(userId, request)).status());
        }
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.resolve(null, new ResolveAgentPicturesRequest(assessmentId, List.of()))).status());
        verifyNoInteractions(pictures, sets, shapes, signer);
    }

    @Test
    void rejectsMissingDeletedOrForeignPictureBeforeAnnotationsOrUrlSigning() throws Exception {
        UUID id = UUID.randomUUID();
        when(pictures.selectList(any())).thenReturn(List.of());
        assertMissing(id);
        PictureAsset foreign = picture(id);
        foreign.setOwnerUserId(UUID.randomUUID());
        when(pictures.selectList(any())).thenReturn(List.of(foreign));
        assertMissing(id);
        PictureAsset deleted = picture(id);
        deleted.setDeletedAt(OffsetDateTime.now());
        when(pictures.selectList(any())).thenReturn(List.of(deleted));
        assertMissing(id);
        verifyNoInteractions(sets, shapes, signer);
    }

    @Test
    void rejectsPendingOrMissingDimensionsBeforeAnnotationReads() throws Exception {
        UUID id = UUID.randomUUID();
        PictureAsset pending = picture(id);
        pending.setStorageStatus(PictureUploadStatus.INITIATED.name());
        when(pictures.selectList(any())).thenReturn(List.of(pending));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.resolve(userId,
                new ResolveAgentPicturesRequest(assessmentId, List.of(id)))).status());
        pending.setStorageStatus(PictureUploadStatus.AVAILABLE.name());
        pending.setWidthPx(null);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.resolve(userId,
                new ResolveAgentPicturesRequest(assessmentId, List.of(id)))).status());
        verifyNoInteractions(sets, shapes, signer);
    }

    @Test
    void refusesMissingUrlInsteadOfReturningAnUnusablePicture() throws Exception {
        UUID id = UUID.randomUUID();
        when(pictures.selectList(any())).thenReturn(List.of(picture(id)));
        when(sets.selectList(any())).thenReturn(List.of());
        ApiException error = assertThrows(ApiException.class, () -> service.resolve(userId,
                new ResolveAgentPicturesRequest(assessmentId, List.of(id))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("PICTURE_URL_UNAVAILABLE", error.code());
    }

    @Test
    void signingErrorDoesNotExposeStorageCoordinates() throws Exception {
        UUID id = UUID.randomUUID();
        when(pictures.selectList(any())).thenReturn(List.of(picture(id)));
        when(sets.selectList(any())).thenReturn(List.of());
        when(signer.signGet(any(), any())).thenThrow(new IllegalStateException("private-key-internal-error"));
        ApiException error = assertThrows(ApiException.class, () -> service.resolve(userId,
                new ResolveAgentPicturesRequest(assessmentId, List.of(id))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertFalse(error.getMessage().contains("private-key"));
    }

    private void assertMissing(UUID id) {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.resolve(userId,
                new ResolveAgentPicturesRequest(assessmentId, List.of(id)))).status());
    }

    private PictureAsset picture(UUID id) throws Exception {
        PictureAsset picture = new PictureAsset();
        picture.setId(id);
        picture.setOwnerUserId(userId);
        picture.setSourceType(VisualSourceType.MODEL_SNAPSHOT.name());
        picture.setStorageStatus(PictureUploadStatus.AVAILABLE.name());
        picture.setWidthPx(800);
        picture.setHeightPx(600);
        picture.setContentType("image/png");
        picture.setBucketName("pictures");
        picture.setObjectKey("users/private/picture.png");
        PGobject view = new PGobject();
        view.setType("jsonb");
        view.setValue("{\"zoom\":1.2}");
        picture.setViewState(view);
        return picture;
    }

    private AnnotationSet annotationSet(UUID pictureId) {
        AnnotationSet set = new AnnotationSet();
        set.setId(UUID.randomUUID());
        set.setPictureId(pictureId);
        set.setAssessmentId(assessmentId);
        set.setOwnerUserId(userId);
        set.setRevision(2);
        set.setCoordinateSpace(CoordinateSpace.NORMALIZED_0_1.name());
        set.setSourceWidth(800);
        set.setSourceHeight(600);
        set.setUpdatedAt(OffsetDateTime.now());
        set.setNote("攀岩后不适");
        return set;
    }
}
