package org.javaup.image.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.javaup.image.api.dto.VisualSourceType;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class PictureAssetFactoryTest {

    @Test
    void createsAnUploadReservationWithoutMarkingItAvailable() {
        UUID owner = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        PictureAsset picture = PictureAsset.create(
                owner, idempotencyKey, VisualSourceType.USER_UPLOAD,
                "symptom-pictures", "users/test/original.jpg", "hand.jpg",
                "image/jpeg", 123L, null
        );

        assertNotNull(picture.getId());
        assertEquals(owner, picture.getOwnerUserId());
        assertEquals(idempotencyKey.toString(), picture.getUploadIdempotencyKey());
        assertEquals("INITIATED", picture.getStorageStatus());
        assertEquals("PENDING", picture.getProcessingStatus());
        assertEquals(1, picture.getProcessingGeneration());
        assertEquals("jsonb", picture.getSafeMetadata().getType());
        assertEquals("{}", picture.getSafeMetadata().getValue());
        assertNotNull(picture.getCreatedAt());
        assertEquals(picture.getCreatedAt(), picture.getUpdatedAt());
        assertNull(picture.getDeletedAt());
        assertNull(picture.getStandardAssetKey());
        assertNull(picture.getViewState());
    }

    @Test
    void keepsSnapshotViewStateSeparateFromSafeMetadata() throws Exception {
        PGobject viewState = new PGobject();
        viewState.setType("jsonb");
        viewState.setValue("{\"modelAssetVersion\":\"hand-v1\"}");

        PictureAsset picture = PictureAsset.create(
                UUID.randomUUID(), UUID.randomUUID(), VisualSourceType.MODEL_SNAPSHOT,
                "symptom-pictures", "snapshots/test.png", "snapshot.png",
                "image/png", 123L, viewState
        );

        assertSame(viewState, picture.getViewState());
        assertEquals("{}", picture.getSafeMetadata().getValue());
    }

    @Test
    void rejectsSystemPicturesAtTheUserUploadFactory() {
        assertThrows(IllegalArgumentException.class, () -> PictureAsset.create(
                UUID.randomUUID(), UUID.randomUUID(), VisualSourceType.STANDARD_REFERENCE,
                "symptom-pictures", "standard/test.png", "test.png", "image/png", 123L, null
        ));
    }

    @Test
    void rejectsMissingOwnershipAndEmptyUploads() {
        assertThrows(NullPointerException.class, () -> PictureAsset.create(
                null, UUID.randomUUID(), VisualSourceType.USER_UPLOAD,
                "symptom-pictures", "test.png", "test.png", "image/png", 123L, null
        ));
        assertThrows(IllegalArgumentException.class, () -> PictureAsset.create(
                UUID.randomUUID(), UUID.randomUUID(), VisualSourceType.USER_UPLOAD,
                "symptom-pictures", "test.png", "test.png", "image/png", 0L, null
        ));
    }
}
