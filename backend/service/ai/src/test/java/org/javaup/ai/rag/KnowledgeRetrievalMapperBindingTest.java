package org.javaup.ai.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.PreparedStatement;
import java.util.Map;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.MybatisConfiguration;

/** 验证真实 MyBatis 参数绑定；不需要数据库或 pgvector 容器。 */
class KnowledgeRetrievalMapperBindingTest {
    @Test
    void vectorAndFilteringParametersAreBoundRatherThanInterpolated() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeRetrievalMapper.class);
        var statement = configuration.getMappedStatement(KnowledgeRetrievalMapper.class.getName() + ".nearest");
        Map<String, Object> params = Map.of(
                "identity", "local:embedding-test", "dimensions", 3,
                "vector", "[1.0,0.0,0.0]", "maxDistance", 0.35,
                "limit", 3, "maxContentChars", 1600);
        BoundSql sql = statement.getBoundSql(params);
        String normalized = sql.getSql().replaceAll("\\s+", " ");
        assertTrue(normalized.startsWith("WITH compatible AS MATERIALIZED"));
        assertTrue(normalized.indexOf("vector_dims(c.embedding) = ?")
                < normalized.indexOf("1 - (embedding <=>"));
        assertTrue(normalized.contains("d.status = 'ACTIVE'"));
        assertTrue(normalized.contains("c.embedding_model = ?"));
        assertTrue(normalized.contains("vector_dims(c.embedding) = ?"));
        assertTrue(normalized.contains("CAST(? AS vector)"));
        assertTrue(normalized.contains("left(c.content, ?)"));
        assertTrue(normalized.endsWith("LIMIT ?"));
        assertFalse(normalized.contains("[1.0"));
        assertFalse(normalized.contains("FOR UPDATE"));
        PreparedStatement prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, params, sql).setParameters(prepared);
        for (int i = 0; i < sql.getParameterMappings().size(); i++) {
            String name = sql.getParameterMappings().get(i).getProperty();
            if (name.equals("vector")) verify(prepared).setString(i + 1, "[1.0,0.0,0.0]");
            if (name.equals("identity")) verify(prepared).setString(i + 1, "local:embedding-test");
        }
    }
}
