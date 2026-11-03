package org.javaup.history.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The database key is {@code (assessment_id, symptom_code)}. Do not use inherited
 * {@code *ById} methods for this entity; the composite-key API will be designed later.
 */
@Getter
@Setter
@TableName("assessment_symptom")
public class AssessmentSymptom {

    private UUID assessmentId;

    private String symptomCode;

    private String detailNote;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
