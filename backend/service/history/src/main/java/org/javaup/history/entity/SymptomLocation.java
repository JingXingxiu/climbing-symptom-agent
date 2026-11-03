package org.javaup.history.entity;
import org.javaup.history.api.dto.SymptomLocationInput;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("symptom_location")
public class SymptomLocation {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID assessmentId;

    private String handSide;

    private String regionId;

    private String regionLabel;

    private String fingerCode;

    private String jointCode;

    private String surfaceCode;

    @TableField("is_primary")
    private Boolean primary;

    private String detailNote;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public static SymptomLocation create(
            UUID assessmentId,
            String assessmentHandSide,
            SymptomLocationInput input,
            boolean primary,
            OffsetDateTime now
    ) {
        SymptomLocation entity = new SymptomLocation();

        entity.setId(UUID.randomUUID());
        entity.setAssessmentId(assessmentId);

        // Assessment.handSide 是唯一真相来源
        entity.setHandSide(assessmentHandSide);

        entity.setRegionId(input.regionId());
        entity.setRegionLabel(input.displayLabel());

        entity.setFingerCode(
                input.fingerCode() == null
                        ? null
                        : input.fingerCode().name()
        );

        entity.setJointCode(
                input.jointCode() == null
                        ? null
                        : input.jointCode().name()
        );

        entity.setPrimary(primary);
        entity.setDetailNote(input.detailNote());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        return entity;
    }
}
