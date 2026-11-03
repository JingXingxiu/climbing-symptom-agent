package org.javaup.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.postgresql.util.PGobject;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("assessment_result")
public class AssessmentResult {

    @TableId(value = "assessment_id", type = IdType.INPUT)
    private UUID assessmentId;

    private String recommendationLevel;

    private String summary;

    /** Raw PostgreSQL jsonb; its API shape is intentionally deferred. */
    @TableField("reasons")
    private PGobject reasons;

    /** Raw PostgreSQL jsonb; its API shape is intentionally deferred. */
    @TableField("suggested_next_actions")
    private PGobject suggestedNextActions;

    /** Raw PostgreSQL jsonb; its API shape is intentionally deferred. */
    @TableField("warning_signs")
    private PGobject warningSigns;

    /** 实际采用的联网/知识库来源快照，API 字段名为 references。 */
    @TableField("source_references")
    private PGobject sourceReferences;

    private String generatedBy;

    private String generatorReference;

    private String safetyNotice;

    private OffsetDateTime finalizedAt;

    private OffsetDateTime updatedAt;
}
