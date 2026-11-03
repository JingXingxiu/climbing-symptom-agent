package org.javaup.ai.rag;

/** 替换 embedding 平台的边界；实现不得把用户原文等额外上下文发送给平台。 */
public interface KnowledgeEmbeddingClient {
    float[] embed(String publicQuery);
}
