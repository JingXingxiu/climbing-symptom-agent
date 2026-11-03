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

/** 可选文档片段与向量；检索实现读取现有数据，不自动切分或导入资料。 */
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

    /** 供检索与引用的片段正文。 */
    private String content;

    /** 正文 token 数，尚未计算时为空。 */
    private Integer tokenCount;

    /** 生成此片段向量的完整身份 provider:model，避免跨平台同名模型混算。 */
    private String embeddingModel;

    /** 对应 embedding 模型的向量维度，选定模型前为空。 */
    private Integer embeddingDimensions;

    /** PostgreSQL vector 原始值（PGobject 的 type=vector）；检索 SQL 直接计算距离。 */
    @TableField("embedding")
    private PGobject embedding;

    /** 片段页码、章节等少量扩展元数据。 */
    @TableField("metadata")
    private PGobject metadata;

    /** 片段记录建立时间。 */
    private OffsetDateTime createdAt;
}
