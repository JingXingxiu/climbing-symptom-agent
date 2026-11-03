package org.javaup.history.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** Consumer-owned lookup contract; no dependency on image-service Java classes. */
@FeignClient(name = "image-service")
public interface ImageClient {

    @PostMapping("/internal/image/visual-references/resolve")
    List<ResolvedVisualReference> resolveVisualReferences(
            @RequestHeader("X-User-Id") UUID currentUserId,
            @RequestBody ResolveVisualReferencesRequest request
    );

    @GetMapping("/api/image/v1/pictures/{pictureId}")
    PictureLookupResponse getPicture(
            @RequestHeader("X-User-Id") UUID currentUserId,
            @PathVariable("pictureId") UUID pictureId
    );

    @GetMapping("/api/image/v1/pictures/{pictureId}/annotation-set")
    AnnotationSetLookupResponse getAnnotationSet(
            @RequestHeader("X-User-Id") UUID currentUserId,
            @PathVariable("pictureId") UUID pictureId,
            @RequestParam("assessmentId") UUID assessmentId
    );

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PictureLookupResponse(
            UUID pictureId,
            UUID ownerUserId,
            VisualSourceType sourceType,
            String uploadStatus
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnnotationSetLookupResponse(
            UUID annotationSetId,
            UUID pictureId
    ) {}

    record ResolveVisualReferencesRequest(
            UUID assessmentId,
            List<UUID> pictureIds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolvedVisualReference(
            UUID pictureId,
            VisualSourceType sourceType,
            UUID annotationSetId
    ) {}
}
