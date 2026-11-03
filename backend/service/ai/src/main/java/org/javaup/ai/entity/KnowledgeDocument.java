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

/** 可选知识文档；只读检索默认关闭，资料与 SQL 需显式准备，不影响 Agent 状态推进。 */
@Getter
@Setter
@TableName("knowledge_document")
public class KnowledgeDocument {

    /** 知识文档 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 文档标题。 */
    private String title;

    /** 文档来源类型。 */
    private String sourceType;

    /** 文档来源地址或内部资源标识。 */
    private String sourceUri;

    /** 来源文档的内容版本。 */
    private String contentVersion;

    /** 原始内容的 SHA-256 摘要，用于识别重复文档。 */
    private String contentSha256;

    /** ACTIVE 或 RETIRED，退役文档不参与后续检索。 */
    private String status;

    /** 少量来源、出处等扩展元数据。 */
    @TableField("metadata")
    private PGobject metadata;

    /** 文档记录建立时间。 */
    private OffsetDateTime createdAt;

    /** 文档元数据最近变更时间。 */
    private OffsetDateTime updatedAt;
}
