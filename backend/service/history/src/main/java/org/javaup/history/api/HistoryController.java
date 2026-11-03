package org.javaup.history.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.javaup.history.api.dto.AppendAssessmentAnswerRequest;
import org.javaup.history.api.dto.AssessmentAnswerResponse;
import org.javaup.history.api.dto.AssessmentPageResponse;
import org.javaup.history.api.dto.AssessmentResponse;
import org.javaup.history.api.dto.AssessmentResultResponse;
import org.javaup.history.api.dto.AssessmentResumeResponse;
import org.javaup.history.api.dto.CreateAssessmentRequest;
import org.javaup.history.api.dto.HistoryEnums.AssessmentStatus;
import org.javaup.history.api.dto.ReplaceVisualReferencesRequest;
import org.javaup.history.api.dto.ReplaceSymptomLocationsRequest;
import org.javaup.history.api.dto.SubmitAssessmentRequest;
import org.javaup.history.api.dto.SymptomLocationResponse;
import org.javaup.history.api.dto.UpdateAssessmentBasicsRequest;
import org.javaup.history.api.dto.VisualReference;
import org.javaup.history.service.AssessmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAPI transport boundary for history-service. Controllers do not decide
 * Assessment workflow or call base/image/ai services in this template.
 */
@Validated
@RestController
@AllArgsConstructor
@RequestMapping(value = "/api/history/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class HistoryController {

    private final AssessmentService assessmentService;

    @PostMapping(value = "/assessments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssessmentResponse> createAssessment(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @RequestHeader("X-Idempotency-Key") @NotNull UUID idempotencyKey,
            @Valid @RequestBody CreateAssessmentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assessmentService.create(currentUserId, idempotencyKey, request));
    }

    @GetMapping("/assessments")
    public ResponseEntity<AssessmentPageResponse> listAssessments(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @RequestParam(required = false) AssessmentStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return ResponseEntity.ok(assessmentService.list(currentUserId, status, cursor, limit));
    }

    @GetMapping("/assessments/{assessmentId}")
    public ResponseEntity<AssessmentResponse> getAssessment(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId
    ) {
        return ResponseEntity.ok(assessmentService.get(currentUserId, assessmentId));
    }

    @PatchMapping(value = "/assessments/{assessmentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssessmentResponse> updateAssessmentBasics(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody UpdateAssessmentBasicsRequest request
    ) {
        return ResponseEntity.ok(assessmentService.updateBasics(currentUserId, assessmentId, request));
    }

    @GetMapping("/assessments/{assessmentId}/resume")
    public ResponseEntity<AssessmentResumeResponse> resumeAssessment(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId
    ) {
        return ResponseEntity.ok(assessmentService.resume(currentUserId, assessmentId));
    }

    @PutMapping(value = "/assessments/{assessmentId}/symptom-locations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<SymptomLocationResponse>> replaceSymptomLocations(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody ReplaceSymptomLocationsRequest request
    ) {
        return ResponseEntity.ok(assessmentService.replaceLocations(currentUserId, assessmentId, request));
    }

    @DeleteMapping("/assessments/{assessmentId}/symptom-locations")
    public ResponseEntity<Void> clearSymptomLocations(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId
    ) {
        assessmentService.clearLocations(currentUserId, assessmentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(value = "/assessments/{assessmentId}/visual-references", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<VisualReference>> replaceVisualReferences(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody ReplaceVisualReferencesRequest request
    ) {
        return ResponseEntity.ok(assessmentService.replaceVisuals(currentUserId, assessmentId, request));
    }

    @PostMapping(value = "/assessments/{assessmentId}/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssessmentAnswerResponse> appendAssessmentAnswer(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody AppendAssessmentAnswerRequest request
    ) {
        return ResponseEntity.ok(assessmentService.appendAnswer(currentUserId, assessmentId, request));
    }

    @PostMapping(value = "/assessments/{assessmentId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssessmentResponse> submitAssessment(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody SubmitAssessmentRequest request
    ) {
        return ResponseEntity.ok(assessmentService.submit(currentUserId, assessmentId, request));
    }

    @GetMapping("/assessments/{assessmentId}/result")
    public ResponseEntity<AssessmentResultResponse> getAssessmentResult(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @PathVariable UUID assessmentId
    ) {
        return ResponseEntity.ok(assessmentService.getResult(currentUserId, assessmentId));
    }
}
