package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import org.postgresql.util.PGobject;

import java.time.OffsetDateTime;
import java.util.UUID;

/** FUTURE：可选文档片段和向量占位，不包含 embedding 生成或查询实现。 */
@Getter
@Setter
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    /** 文档片段 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属知识文档 ID。 */
    private UUID documentId;

    /** 文档内片段序号，从 0 开始。 */
    private Integer chunkNo;

    /** 供未来检索与引用的片段正文。 */
    private String content;

    /** 正文 token 数，尚未计算时为空。 */
    private Integer tokenCount;

    /** 生成此片段向量的 embedding 模型。 */
    private String embeddingModel;

    /** 对应 embedding 模型的向量维度，选定模型前为空。 */
    private Integer embeddingDimensions;

    /** PostgreSQL vector 原始值占位（PGobject 的 type=vector），当前无读写或 SDK。 */
    @TableField("embedding")
    private PGobject embedding;

    /** 片段页码、章节等少量扩展元数据。 */
    @TableField("metadata")
    private PGobject metadata;

    /** 片段记录建立时间。 */
    private OffsetDateTime createdAt;
}
