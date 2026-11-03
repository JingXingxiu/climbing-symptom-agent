package org.javaup.image.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.javaup.image.api.dto.ResolveVisualReferencesRequest;
import org.javaup.image.api.dto.ResolvedVisualReference;
import org.javaup.image.service.PictureAssetService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API. The internal URL is not itself an authentication mechanism. */
@Validated
@RestController
@AllArgsConstructor
@RequestMapping(value = "/internal/image", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalImageController {

    private final PictureAssetService pictureAssetService;

    @PostMapping(value = "/visual-references/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<ResolvedVisualReference> resolveVisualReferences(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @Valid @RequestBody ResolveVisualReferencesRequest request
    ) {
        return pictureAssetService.resolveVisualReferences(currentUserId, request);
    }
}
