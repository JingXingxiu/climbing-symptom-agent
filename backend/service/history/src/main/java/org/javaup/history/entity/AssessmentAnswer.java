package org.javaup.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.javaup.history.api.dto.AppendAssessmentAnswerRequest;
import org.postgresql.util.PGobject;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@TableName("assessment_answer")
public class AssessmentAnswer {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID assessmentId;

    private UUID clientAnswerId;

    private Integer sequenceNo;

    private String questionKey;

    private String questionText;

    /** Structured caller-provided answer, retained without inventing Agent output. */
    @TableField("answer")
    private PGobject answer;

    private String answerSource;

    private OffsetDateTime createdAt;

    public static AssessmentAnswer create(
            UUID assessmentId,
            AppendAssessmentAnswerRequest request,
            OffsetDateTime now,
            ObjectMapper objectMapper
    ) {
        Objects.requireNonNull(assessmentId, "assessmentId is required");
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(now, "createdAt is required");
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        if (request.answer() == null || request.answer().isNull() || request.answer().isMissingNode()) {
            throw new IllegalArgumentException("A non-null JSON answer is required");
        }

        AssessmentAnswer entity = new AssessmentAnswer();
        entity.id = UUID.randomUUID();
        entity.assessmentId = assessmentId;
        entity.clientAnswerId = request.clientAnswerId();
        entity.sequenceNo = request.sequenceNumber();
        entity.questionKey = request.questionKey();
        entity.questionText = request.questionText();
        entity.answerSource = request.source().name();
        entity.createdAt = now;

        PGobject json = new PGobject();
        json.setType("jsonb");
        try {
            json.setValue(objectMapper.writeValueAsString(request.answer()));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to prepare answer JSON", exception);
        }
        entity.answer = json;
        return entity;
    }
}
