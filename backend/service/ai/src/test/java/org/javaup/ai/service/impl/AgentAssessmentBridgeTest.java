package org.javaup.ai.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.javaup.ai.api.dto.AgentEnums.RecommendationLevel;
import org.javaup.ai.api.dto.AgentOutcomeResponse;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.client.AgentHistoryClient;
import org.javaup.ai.client.AgentImageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class AgentAssessmentBridgeTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final UUID pictureId = UUID.randomUUID();
    private final UUID annotationId = UUID.randomUUID();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private AgentHistoryClient history;
    private AgentImageClient image;
    private AgentAssessmentBridgeImpl bridge;

    @BeforeEach
    void setup() {
        history = mock(AgentHistoryClient.class);
        image = mock(AgentImageClient.class);
        bridge = new AgentAssessmentBridgeImpl(history, image, mapper);
        when(history.getInput(userId, assessmentId)).thenReturn(input());
        when(image.resolve(eq(userId), any()))
                .thenReturn(List.of(picture("https://images.invalid/old-signed-url")));
    }

    @Test
    void freezesAnnotationAndViewStateWithoutSignedUrlOrFlatAgentHistory() {
        var snapshot = bridge.loadSnapshot(userId, assessmentId);
        assertEquals(7, snapshot.assessmentVersion());
        assertEquals(
                "a".repeat(64),
                snapshot.inputSnapshot().path("assessmentInputFingerprint").asText());
        var picture = snapshot.inputSnapshot().path("pictures").get(0);
        assertEquals(
                annotationId.toString(),
                picture.path("annotation").path("annotationSetId").asText());
        assertEquals(3, picture.path("annotation").path("version").asInt());
        assertFalse(snapshot.inputSnapshot().toString().contains("signed-url"));
        assertFalse(snapshot.inputSnapshot().has("answers"));
        verify(image, times(1)).resolve(eq(userId), any());
    }

    @Test
    void refreshesPictureUrlWithoutReplacingFrozenAnnotation() {
        var snapshot = bridge.loadSnapshot(userId, assessmentId);
        var changed = picture("https://images.invalid/new-signed-url");
        ((tools.jackson.databind.node.ObjectNode) changed.annotation()).put("version", 9);
        when(image.resolve(eq(userId), any())).thenReturn(List.of(changed));
        var pictures = bridge.resolvePictures(userId, snapshot.inputSnapshot());
        assertEquals("https://images.invalid/new-signed-url", pictures.get(0).url().toString());
        assertEquals(
                3,
                snapshot.inputSnapshot()
                        .path("pictures")
                        .get(0)
                        .path("annotation")
                        .path("version")
                        .asInt());
    }

    @Test
    void rejectsHistoryOwnerMismatch() {
        var current = input();
        when(history.getInput(userId, assessmentId))
                .thenReturn(
                        new AgentHistoryClient.InputResponse(
                                assessmentId,
                                UUID.randomUUID(),
                                current.assessmentVersion(),
                                current.submittedAt(),
                                current.assessmentInputFingerprint(),
                                current.assessment()));
        assertEquals(
                "INVALID_ASSESSMENT_INPUT",
                assertThrows(ApiException.class, () -> bridge.loadSnapshot(userId, assessmentId))
                        .code());
        verifyNoInteractions(image);
    }

    @Test
    void rejectsOmittedOrUnreadablePictureInsteadOfSendingNullUrl() {
        when(image.resolve(eq(userId), any())).thenReturn(List.of());
        assertEquals(
                "INVALID_PICTURE_SNAPSHOT",
                assertThrows(ApiException.class, () -> bridge.loadSnapshot(userId, assessmentId))
                        .code());
        when(image.resolve(eq(userId), any()))
                .thenReturn(
                        List.of(
                                new AgentImageClient.PictureResponse(
                                        pictureId,
                                        "USER_UPLOAD",
                                        "image/jpeg",
                                        800,
                                        600,
                                        null,
                                        null,
                                        null)));
        assertEquals(
                "INVALID_PICTURE_SNAPSHOT",
                assertThrows(ApiException.class, () -> bridge.loadSnapshot(userId, assessmentId))
                        .code());
    }

    @Test
    void rejectsAReferencedAnnotationThatWasRemoved() {
        var picture = picture("https://images.invalid/valid");
        when(image.resolve(eq(userId), any()))
                .thenReturn(
                        List.of(
                                new AgentImageClient.PictureResponse(
                                        pictureId,
                                        picture.sourceType(),
                                        picture.mediaType(),
                                        picture.width(),
                                        picture.height(),
                                        null,
                                        null,
                                        picture.originalUrl())));
        assertEquals(
                "ASSESSMENT_ANNOTATION_CHANGED",
                assertThrows(ApiException.class, () -> bridge.loadSnapshot(userId, assessmentId))
                        .code());
    }

    @Test
    void forwardsMockProvenanceAttemptAndFingerprintToHistory() {
        UUID session = UUID.randomUUID();
        UUID step = UUID.randomUUID();
        UUID checkpoint = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Instant time = Instant.parse("2026-10-27T00:00:00Z");
        var outcome =
                new AgentOutcomeResponse(
                        session,
                        assessmentId,
                        RecommendationLevel.ROUTINE_PROFESSIONAL,
                        "demo",
                        List.of(),
                        List.of(),
                        List.of(),
                        "不是医学诊断",
                        time,
                        null);
        when(history.finalizeResult(eq(userId), eq(assessmentId), any()))
                .thenReturn(new AgentHistoryClient.FinalizedResponse(resultId, 2, time));
        var result =
                bridge.finalizeResult(
                        userId,
                        session,
                        step,
                        checkpoint,
                        outcome,
                        "v1",
                        3,
                        "MOCK",
                        "a".repeat(64));
        assertEquals(resultId, result.resultId());
        var captor = ArgumentCaptor.forClass(AgentHistoryClient.FinalizeRequest.class);
        verify(history).finalizeResult(eq(userId), eq(assessmentId), captor.capture());
        assertEquals(3, captor.getValue().attemptNo());
        assertEquals("MOCK", captor.getValue().generatedBy());
        assertEquals("a".repeat(64), captor.getValue().assessmentInputFingerprint());
    }

    private AgentHistoryClient.InputResponse input() {
        JsonNode aggregate =
                mapper.readTree(
                        """
                {"initialInput":{"handSide":"RIGHT"},"symptomLocations":[],
                "visualReferences":[{"pictureId":"%s","annotationSetId":"%s","sourceType":"USER_UPLOAD"}],
                "answers":[{"questionKey":"old-branch-answer"}]}
                """
                                .formatted(pictureId, annotationId));
        return new AgentHistoryClient.InputResponse(
                assessmentId, userId, 7, Instant.now(), "a".repeat(64), aggregate);
    }

    private AgentImageClient.PictureResponse picture(String url) {
        JsonNode annotation =
                mapper.readTree(
                        """
                {"annotationSetId":"%s","version":3,"coordinateSpace":"NORMALIZED", "shapes":[]}
                """
                                .formatted(annotationId));
        return new AgentImageClient.PictureResponse(
                pictureId,
                "USER_UPLOAD",
                "image/jpeg",
                800,
                600,
                null,
                annotation,
                URI.create(url));
    }
}
