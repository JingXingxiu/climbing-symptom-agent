package org.javaup.image.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.javaup.image.api.dto.AnnotationGeometry;
import org.javaup.image.api.dto.AnnotationSetResponse;
import org.javaup.image.api.dto.AnnotationShape;
import org.javaup.image.api.dto.AnnotationShapeType;
import org.javaup.image.api.dto.CoordinateSpace;
import org.javaup.image.api.dto.NormalizedPoint;
import org.javaup.image.api.dto.ReplaceAnnotationSetRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnnotationFactoriesTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void createsScopedDraftAtRevisionOneWithSourceDimensions() {
        UUID owner = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T08:00:00+08:00");
        var request = new ReplaceAnnotationSetRequest(
                assessmentId, 1200, 800, List.of(), "右手指关节不适", 0);

        AnnotationSet entity = AnnotationSet.create(owner, pictureId, request, now);

        assertNotNull(entity.getId());
        assertEquals(owner, entity.getOwnerUserId());
        assertEquals(pictureId, entity.getPictureId());
        assertEquals(assessmentId, entity.getAssessmentId());
        assertEquals("DRAFT", entity.getStatus());
        assertEquals("NORMALIZED_0_1", entity.getCoordinateSpace());
        assertEquals(1, entity.getSchemaVersion());
        assertEquals(1, entity.getRevision());
        assertEquals(1200, entity.getSourceWidth());
        assertEquals(800, entity.getSourceHeight());
        assertEquals("右手指关节不适", entity.getNote());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
    }

    @Test
    void appliesReplacementIncludingNullNoteWithoutChangingIdentityOrCreationTime() {
        UUID owner = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-22T08:00:00+08:00");
        var original = new ReplaceAnnotationSetRequest(
                assessmentId, 1200, 800, List.of(), "旧备注", 0);
        AnnotationSet entity = AnnotationSet.create(owner, pictureId, original, createdAt);
        UUID id = entity.getId();
        OffsetDateTime updatedAt = createdAt.plusMinutes(5);

        entity.applyContent(new ReplaceAnnotationSetRequest(
                assessmentId, 600, 400, List.of(), null, 1), 2, updatedAt);

        assertEquals(id, entity.getId());
        assertEquals(owner, entity.getOwnerUserId());
        assertEquals(pictureId, entity.getPictureId());
        assertEquals(assessmentId, entity.getAssessmentId());
        assertEquals("DRAFT", entity.getStatus());
        assertEquals(1, entity.getSchemaVersion());
        assertEquals(2, entity.getRevision());
        assertEquals(600, entity.getSourceWidth());
        assertEquals(400, entity.getSourceHeight());
        assertNull(entity.getNote());
        assertEquals(createdAt, entity.getCreatedAt());
        assertEquals(updatedAt, entity.getUpdatedAt());
    }

    @Test
    void roundTripsAllGeometryKindsAndNullableStyleThroughJsonbAndResponse() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T08:00:00+08:00");
        NormalizedPoint first = new NormalizedPoint(0.2, 0.3);
        NormalizedPoint second = new NormalizedPoint(0.5, 0.7);
        List<AnnotationShape> shapes = List.of(
                new AnnotationShape(UUID.randomUUID(), AnnotationShapeType.POINT,
                        new AnnotationGeometry.Point(first), "#Aa00FF", 0.01, "掌侧麻木"),
                new AnnotationShape(UUID.randomUUID(), AnnotationShapeType.CIRCLE,
                        new AnnotationGeometry.Circle(first, 0.1, 0.2), null, null, null),
                new AnnotationShape(UUID.randomUUID(), AnnotationShapeType.POLYLINE,
                        new AnnotationGeometry.Polyline(List.of(first, second)), null, 0.02, "酸胀路径"),
                new AnnotationShape(UUID.randomUUID(), AnnotationShapeType.POLYGON,
                        new AnnotationGeometry.Polygon(List.of(first, second)), "#FF0000", null, "不适区域")
        );
        var request = new ReplaceAnnotationSetRequest(
                UUID.randomUUID(), 1200, 800, shapes, "弯曲时明显", 0);
        AnnotationSet annotationSet = AnnotationSet.create(
                UUID.randomUUID(), UUID.randomUUID(), request, now);
        List<PictureAnnotation> entities = new ArrayList<>();
        List<String> kinds = List.of("point", "circle", "polyline", "polygon");

        for (int index = 0; index < shapes.size(); index++) {
            PictureAnnotation entity = PictureAnnotation.create(
                    annotationSet.getId(), shapes.get(index), index, now, objectMapper);
            entities.add(entity);

            assertEquals(shapes.get(index).shapeId(), entity.getId());
            assertEquals(annotationSet.getId(), entity.getAnnotationSetId());
            assertEquals(shapes.get(index).type().name(), entity.getShapeType());
            assertEquals(index, entity.getSortOrder());
            assertEquals(now, entity.getCreatedAt());
            assertEquals(now, entity.getUpdatedAt());
            assertEquals("jsonb", entity.getGeometry().getType());
            assertEquals("jsonb", entity.getStyle().getType());
            assertEquals(kinds.get(index), objectMapper.readTree(
                    entity.getGeometry().getValue()).get("kind").asText());
        }

        assertEquals("{}", entities.get(1).getStyle().getValue());
        assertTrue(entities.get(2).getStyle().getValue().contains("lineWidthNormalized"));
        AnnotationSetResponse response = AnnotationSetResponse.fromEntity(
                annotationSet, entities, objectMapper);

        assertEquals(annotationSet.getId(), response.annotationSetId());
        assertEquals(annotationSet.getPictureId(), response.pictureId());
        assertEquals(CoordinateSpace.NORMALIZED_0_1, response.coordinateSpace());
        assertEquals(1200, response.sourceWidth());
        assertEquals(800, response.sourceHeight());
        assertEquals(shapes, response.shapes());
        assertEquals("弯曲时明显", response.note());
        assertEquals(1, response.version());
        assertEquals(now.toInstant(), response.updatedAt());
    }

    @Test
    void returnsEmptyShapesAfterAWholeSetClear() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T08:00:00+08:00");
        AnnotationSet entity = AnnotationSet.create(UUID.randomUUID(), UUID.randomUUID(),
                new ReplaceAnnotationSetRequest(UUID.randomUUID(), 800, 600, List.of(), null, 0), now);

        AnnotationSetResponse response = AnnotationSetResponse.fromEntity(entity, List.of(), objectMapper);

        assertEquals(List.of(), response.shapes());
        assertNull(response.note());
    }
}
