package org.javaup.history.service;

import com.baomidou.mybatisplus.spring.service.IService;
import java.util.List;
import java.util.UUID;
import org.javaup.history.api.dto.AppendAssessmentAnswerRequest;
import org.javaup.history.api.dto.AssessmentAnswerResponse;
import org.javaup.history.api.dto.AssessmentPageResponse;
import org.javaup.history.api.dto.AssessmentResponse;
import org.javaup.history.api.dto.AssessmentResultResponse;
import org.javaup.history.api.dto.AssessmentResumeResponse;
import org.javaup.history.api.dto.CreateAssessmentRequest;
import org.javaup.history.api.dto.HistoryEnums.AssessmentStatus;
import org.javaup.history.api.dto.ReplaceSymptomLocationsRequest;
import org.javaup.history.api.dto.ReplaceVisualReferencesRequest;
import org.javaup.history.api.dto.SubmitAssessmentRequest;
import org.javaup.history.api.dto.SymptomLocationResponse;
import org.javaup.history.api.dto.UpdateAssessmentBasicsRequest;
import org.javaup.history.api.dto.VisualReference;
import org.javaup.history.entity.Assessment;

public interface AssessmentService extends IService<Assessment> {

    AssessmentResponse create(UUID currentUserId, UUID idempotencyKey, CreateAssessmentRequest request);

    AssessmentPageResponse list(UUID currentUserId, AssessmentStatus status, String cursor, int limit);

    AssessmentResponse get(UUID currentUserId, UUID assessmentId);

    AssessmentResponse updateBasics(UUID currentUserId, UUID assessmentId, UpdateAssessmentBasicsRequest request);

    AssessmentResumeResponse resume(UUID currentUserId, UUID assessmentId);

    List<SymptomLocationResponse> replaceLocations(
            UUID currentUserId,
            UUID assessmentId,
            ReplaceSymptomLocationsRequest request
    );

    void clearLocations(UUID currentUserId, UUID assessmentId);

    List<VisualReference> replaceVisuals(
            UUID currentUserId,
            UUID assessmentId,
            ReplaceVisualReferencesRequest request
    );

    AssessmentAnswerResponse appendAnswer(
            UUID currentUserId,
            UUID assessmentId,
            AppendAssessmentAnswerRequest request
    );

    AssessmentResponse submit(UUID currentUserId, UUID assessmentId, SubmitAssessmentRequest request);

    AssessmentResultResponse getResult(UUID currentUserId, UUID assessmentId);
}
