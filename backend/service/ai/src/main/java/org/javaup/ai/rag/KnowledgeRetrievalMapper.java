package org.javaup.ai.rag;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** SQL 只使用绑定参数；没有动态表名、向量拼接或检索期间的行锁。 */
@Mapper
public interface KnowledgeRetrievalMapper {

    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE d.status = 'ACTIVE'
                  AND c.embedding IS NOT NULL
                  AND length(trim(c.content)) > 0
            )
            """)
    boolean hasAnySearchableChunks();

    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE d.status = 'ACTIVE'
                  AND c.embedding_model = #{identity}
                  AND c.embedding_dimensions = #{dimensions}
                  AND vector_dims(c.embedding) = #{dimensions}
                  AND c.embedding IS NOT NULL
                  AND length(trim(c.content)) > 0
            )
            """)
    boolean hasCompatibleChunks(
            @Param("identity") String identity,
            @Param("dimensions") int dimensions
    );

    @Select("""
            WITH compatible AS MATERIALIZED (
                SELECT d.id AS document_id, c.id AS chunk_id,
                       d.title, d.source_uri, d.content_version,
                       left(c.content, #{maxContentChars}) AS content, c.embedding
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE d.status = 'ACTIVE'
                  AND c.embedding_model = #{identity}
                  AND c.embedding_dimensions = #{dimensions}
                  AND vector_dims(c.embedding) = #{dimensions}
                  AND c.embedding IS NOT NULL
                  AND length(trim(c.content)) > 0
            )
            SELECT document_id, chunk_id, title, source_uri, content_version, content,
                   1 - (embedding <=> CAST(#{vector} AS vector)) AS score
            FROM compatible
            WHERE (embedding <=> CAST(#{vector} AS vector)) <= #{maxDistance}
            ORDER BY embedding <=> CAST(#{vector} AS vector), chunk_id
            LIMIT #{limit}
            """)
    List<KnowledgeReference> nearest(
            @Param("identity") String identity,
            @Param("dimensions") int dimensions,
            @Param("vector") String vector,
            @Param("maxDistance") double maxDistance,
            @Param("limit") int limit,
            @Param("maxContentChars") int maxContentChars
    );
}
