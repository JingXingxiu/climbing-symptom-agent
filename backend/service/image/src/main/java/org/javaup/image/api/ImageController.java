package org.javaup.image.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import org.javaup.image.api.dto.AnnotationSetResponse;
import org.javaup.image.api.dto.HandSide;
import org.javaup.image.api.dto.PictureResponse;
import org.javaup.image.api.dto.PictureUploadMetadata;
import org.javaup.image.api.dto.ReplaceAnnotationSetRequest;
import org.javaup.image.api.dto.StandardPictureView;
import org.javaup.image.api.problem.ApiException;
import org.javaup.image.service.PictureAssetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAPI transport boundary for image-service.
 */
@Validated
@RestController
@RequestMapping(value = "/api/image/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ImageController {

    private final PictureAssetService pictureAssetService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ImageController(PictureAssetService pictureAssetService, ObjectMapper objectMapper, Validator validator) {
        this.pictureAssetService = pictureAssetService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping(value = "/pictures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PictureResponse> uploadPicture(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @RequestHeader("X-Idempotency-Key") @NotNull UUID idempotencyKey,
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") byte[] metadataJson
    ) {
        PictureUploadMetadata metadata = parseUploadMetadata(metadataJson);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(pictureAssetService.uploadPicture(currentUserId, idempotencyKey, file, metadata));
    }

    private PictureUploadMetadata parseUploadMetadata(byte[] metadataJson) {
        PictureUploadMetadata metadata;
        try {
            metadata = objectMapper.readValue(metadataJson, PictureUploadMetadata.class);
        } catch (JacksonException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Multipart metadata is malformed JSON.");
        }
        if (metadata == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Multipart metadata must be a JSON object.");
        }
        var violations = validator.validate(metadata);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return metadata;
    }

    @GetMapping("/standard-pictures")
    public ResponseEntity<List<PictureResponse>> listStandardPictures(
            @RequestParam(required = false) HandSide handSide,
            @RequestParam(required = false) StandardPictureView view
    ) {
        return ResponseEntity.ok(pictureAssetService.listStandardPictures(handSide, view));
    }

    @GetMapping("/pictures/{pictureId}")
    public ResponseEntity<PictureResponse> getPicture(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID pictureId
    ) {
        return ResponseEntity.ok(pictureAssetService.getPicture(currentUserId, pictureId));
    }

    @DeleteMapping("/pictures/{pictureId}")
    public ResponseEntity<Void> deletePicture(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID pictureId
    ) {
        pictureAssetService.deletePicture(currentUserId, pictureId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pictures/{pictureId}/annotation-set")
    public ResponseEntity<AnnotationSetResponse> getPictureAnnotationSet(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID pictureId,
            @RequestParam UUID assessmentId
    ) {
        return ResponseEntity.ok(
                pictureAssetService.getPictureAnnotationSet(currentUserId, pictureId, assessmentId)
        );
    }

    @PutMapping(value = "/pictures/{pictureId}/annotation-set", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnnotationSetResponse> replacePictureAnnotationSet(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID pictureId,
            @Valid @org.springframework.web.bind.annotation.RequestBody ReplaceAnnotationSetRequest request
    ) {
        return ResponseEntity.ok(
                pictureAssetService.replacePictureAnnotationSet(currentUserId, pictureId, request)
        );
    }
}
