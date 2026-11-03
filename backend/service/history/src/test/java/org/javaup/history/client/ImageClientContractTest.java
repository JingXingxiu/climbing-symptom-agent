package org.javaup.history.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.UUID;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ImageClientContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void discoversImageServiceWithoutHardCodingAnEndpoint() {
        FeignClient client = ImageClient.class.getAnnotation(FeignClient.class);

        assertNotNull(client);
        assertEquals("image-service", client.name());
        assertEquals("", client.url());
    }

    @Test
    void declaresInternalBatchRouteWithExplicitUserAndJsonBody() throws Exception {
        Method method = ImageClient.class.getMethod("resolveVisualReferences", UUID.class,
                ImageClient.ResolveVisualReferencesRequest.class);

        assertArrayEquals(new String[] {"/internal/image/visual-references/resolve"},
                method.getAnnotation(PostMapping.class).value());
        assertEquals("X-User-Id", method.getParameters()[0]
                .getAnnotation(RequestHeader.class).value());
        assertNotNull(method.getParameters()[1].getAnnotation(RequestBody.class));
        assertEquals(List.class, method.getReturnType());
        assertEquals(ImageClient.ResolvedVisualReference.class,
                ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0]);
    }

    @Test
    void serializesBatchScopeAndReadsOptionalAnnotationWithoutImageDtoDependency() {
        UUID assessmentId = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        var request = new ImageClient.ResolveVisualReferencesRequest(assessmentId, List.of(pictureId));
        var json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertEquals(assessmentId.toString(), json.path("assessmentId").asText());
        assertEquals(pictureId.toString(), json.path("pictureIds").get(0).asText());

        var response = objectMapper.readValue("""
                {
                  "pictureId": "%s",
                  "sourceType": "STANDARD_REFERENCE",
                  "annotationSetId": null,
                  "futureField": "ignored"
                }
                """.formatted(pictureId), ImageClient.ResolvedVisualReference.class);

        assertEquals(pictureId, response.pictureId());
        assertEquals(VisualSourceType.STANDARD_REFERENCE, response.sourceType());
        assertNull(response.annotationSetId());
    }

    @Test
    void declaresPictureRouteAndExplicitUserAndPictureParameters() throws Exception {
        Method method = ImageClient.class.getMethod("getPicture", UUID.class, UUID.class);

        assertArrayEquals(new String[] {"/api/image/v1/pictures/{pictureId}"},
                method.getAnnotation(GetMapping.class).value());
        assertEquals("X-User-Id", method.getParameters()[0]
                .getAnnotation(RequestHeader.class).value());
        assertEquals("pictureId", method.getParameters()[1]
                .getAnnotation(PathVariable.class).value());
        assertEquals(ImageClient.PictureLookupResponse.class, method.getReturnType());
    }

    @Test
    void declaresAnnotationRouteAndExplicitAssessmentScope() throws Exception {
        Method method = ImageClient.class.getMethod("getAnnotationSet", UUID.class, UUID.class, UUID.class);

        assertArrayEquals(new String[] {"/api/image/v1/pictures/{pictureId}/annotation-set"},
                method.getAnnotation(GetMapping.class).value());
        assertEquals("X-User-Id", method.getParameters()[0]
                .getAnnotation(RequestHeader.class).value());
        assertEquals("pictureId", method.getParameters()[1]
                .getAnnotation(PathVariable.class).value());
        RequestParam assessment = method.getParameters()[2].getAnnotation(RequestParam.class);
        assertEquals("assessmentId", assessment.value());
        assertEquals(true, assessment.required());
        assertEquals(ImageClient.AnnotationSetLookupResponse.class, method.getReturnType());
    }

    @Test
    void readsOnlyRequiredPictureFieldsAndIgnoresFullImageResponseFields() {
        UUID pictureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String json = """
                {
                  "pictureId": "%s",
                  "ownerUserId": "%s",
                  "sourceType": "USER_UPLOAD",
                  "uploadStatus": "AVAILABLE",
                  "processingStatus": "PENDING",
                  "byteSize": 1024,
                  "width": 1200,
                  "height": 800,
                  "originalUrl": "https://example.invalid/object",
                  "thumbnailUrl": null,
                  "viewState": null
                }
                """.formatted(pictureId, owner);

        ImageClient.PictureLookupResponse response = objectMapper.readValue(
                json, ImageClient.PictureLookupResponse.class);

        assertEquals(pictureId, response.pictureId());
        assertEquals(owner, response.ownerUserId());
        assertEquals(VisualSourceType.USER_UPLOAD, response.sourceType());
        assertEquals("AVAILABLE", response.uploadStatus());
    }

    @Test
    void permitsNullOwnerForSharedStandardPictures() {
        UUID pictureId = UUID.randomUUID();
        var response = objectMapper.readValue("""
                {
                  "pictureId": "%s",
                  "ownerUserId": null,
                  "sourceType": "STANDARD_REFERENCE",
                  "uploadStatus": "AVAILABLE"
                }
                """.formatted(pictureId), ImageClient.PictureLookupResponse.class);

        assertEquals(pictureId, response.pictureId());
        assertNull(response.ownerUserId());
        assertEquals(VisualSourceType.STANDARD_REFERENCE, response.sourceType());
    }

    @Test
    void readsAnnotationIdentityWithoutDependingOnItsGeometryDto() {
        UUID annotationSetId = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        var response = objectMapper.readValue("""
                {
                  "annotationSetId": "%s",
                  "pictureId": "%s",
                  "coordinateSpace": "NORMALIZED_0_1",
                  "sourceWidth": 1200,
                  "sourceHeight": 800,
                  "shapes": [{"type": "POINT", "geometry": {"kind": "point"}}],
                  "note": "annotation content is owned by image-service",
                  "version": 3,
                  "updatedAt": "2026-10-23T00:00:00Z"
                }
                """.formatted(annotationSetId, pictureId), ImageClient.AnnotationSetLookupResponse.class);

        assertEquals(annotationSetId, response.annotationSetId());
        assertEquals(pictureId, response.pictureId());
    }
}
