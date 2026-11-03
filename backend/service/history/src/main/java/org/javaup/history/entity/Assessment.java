package org.javaup.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.javaup.history.api.dto.HistoryEnums;
import org.javaup.history.api.dto.InitialSymptomDetails;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@TableName("assessment")
public class Assessment {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    @TableField("create_idempotency_key")
    private UUID createIdempotencyKey;

    private String status;

    private String currentStepKey;

    private String handSide;

    private String onsetKind;

    private Integer onsetAmount;

    private LocalDate onsetDate;

    private String onsetText;

    private String triggerContext;

    private String normalUseImpact;

    private String additionalNote;

    private Integer version;

    private OffsetDateTime lastSavedAt;

    private OffsetDateTime completedAt;

    /** First/latest accepted submission time, independent of client-controlled navigation. */
    private OffsetDateTime submittedAt;

    /** Expected Assessment version supplied by the last accepted submit request. */
    private Integer lastSubmitVersion;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public static Assessment create(UUID userId, UUID createIdempotencyKey, InitialSymptomDetails input) {
        Assessment entity = new Assessment();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setCreateIdempotencyKey(createIdempotencyKey);
        entity.setStatus(HistoryEnums.AssessmentStatus.DRAFT.name());
        entity.setCurrentStepKey(HistoryEnums.CurrentStepKey.BASIC_INPUT.name());
        entity.setVersion(1);

        if (input != null) {
            entity.applyInitialInput(input);
        }

        return entity;
    }

    public void applyBasics(
            InitialSymptomDetails input,
            HistoryEnums.CurrentStepKey nextStepKey,
            int nextVersion,
            OffsetDateTime savedAt
    ) {
        applyInitialInput(Objects.requireNonNull(input, "input must not be null"));

        if (nextStepKey != null) {
            setCurrentStepKey(nextStepKey.name());
        }

        if (HistoryEnums.AssessmentStatus.DRAFT.name().equals(status)) {
            setStatus(HistoryEnums.AssessmentStatus.COLLECTING.name());
        }

        setVersion(nextVersion);
        setLastSavedAt(Objects.requireNonNull(savedAt, "savedAt must not be null"));
        setUpdatedAt(savedAt);
    }

    public void markSubmitted(int expectedVersion, OffsetDateTime savedAt) {
        if (HistoryEnums.AssessmentStatus.DRAFT.name().equals(status)) {
            status = HistoryEnums.AssessmentStatus.COLLECTING.name();
        }
        currentStepKey = HistoryEnums.CurrentStepKey.AGENT.name();
        lastSubmitVersion = expectedVersion;
        version = expectedVersion + 1;
        submittedAt = Objects.requireNonNull(savedAt, "savedAt must not be null");
        lastSavedAt = savedAt;
        updatedAt = savedAt;
    }

    public void markAnswerSaved(int nextVersion, OffsetDateTime savedAt) {
        version = nextVersion;
        lastSavedAt = Objects.requireNonNull(savedAt, "savedAt must not be null");
        updatedAt = savedAt;
    }

    private void applyInitialInput(InitialSymptomDetails input) {
        setHandSide(input.handSide().name());
        setOnsetKind(input.onsetKind().name());
        setOnsetAmount(input.onsetAmount());
        setOnsetDate(input.onsetDate());
        setOnsetText(input.onsetText());
        setTriggerContext(input.activityContext());
        setNormalUseImpact(input.normalUseImpact().name());
        setAdditionalNote(input.userNote());
    }
}
