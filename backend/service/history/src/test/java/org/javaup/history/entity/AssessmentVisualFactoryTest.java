package org.javaup.history.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;
import org.javaup.history.api.dto.VisualReference;
import org.junit.jupiter.api.Test;

class AssessmentVisualFactoryTest {

    @Test
    void createsReferenceWithOwnedIdsSourceOrderAndCreationTime() {
        UUID assessmentId = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        UUID annotationSetId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-10-23T08:00:00+08:00");

        AssessmentVisual entity = AssessmentVisual.create(
                assessmentId, pictureId, annotationSetId, VisualSourceType.MODEL_SNAPSHOT, 2, now);

        assertNotNull(entity.getId());
        assertEquals(assessmentId, entity.getAssessmentId());
        assertEquals(pictureId, entity.getPictureId());
        assertEquals(annotationSetId, entity.getAnnotationSetId());
        assertEquals("MODEL_SNAPSHOT", entity.getSourceType());
        assertEquals(2, entity.getSortOrder());
        assertEquals(now, entity.getCreatedAt());
    }

    @Test
    void permitsPictureReferenceWithoutAnyAnnotationSet() {
        AssessmentVisual entity = AssessmentVisual.create(
                UUID.randomUUID(), UUID.randomUUID(), null,
                VisualSourceType.STANDARD_REFERENCE, 0, OffsetDateTime.now());

        assertNull(entity.getAnnotationSetId());
        assertEquals("STANDARD_REFERENCE", entity.getSourceType());
        assertEquals(0, entity.getSortOrder());
        assertNull(VisualReference.fromEntity(entity).annotationSetId());
    }

    @Test
    void allocatesAnIndependentLinkIdOnEachCreation() {
        UUID assessmentId = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        AssessmentVisual first = AssessmentVisual.create(
                assessmentId, pictureId, null, VisualSourceType.USER_UPLOAD, 0, now);
        AssessmentVisual second = AssessmentVisual.create(
                assessmentId, pictureId, null, VisualSourceType.USER_UPLOAD, 0, now);

        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void mapsAllResponseFieldsWithoutExposingPersistenceIdentity() {
        UUID pictureId = UUID.randomUUID();
        UUID annotationSetId = UUID.randomUUID();
        AssessmentVisual entity = AssessmentVisual.create(
                UUID.randomUUID(), pictureId, annotationSetId,
                VisualSourceType.USER_UPLOAD, 4, OffsetDateTime.now());

        VisualReference response = VisualReference.fromEntity(entity);

        assertEquals(new VisualReference(pictureId, VisualSourceType.USER_UPLOAD,
                annotationSetId, 4), response);
    }
}
