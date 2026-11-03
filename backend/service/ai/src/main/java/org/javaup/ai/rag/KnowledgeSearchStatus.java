package org.javaup.ai.rag;

/** 检索状态明确区分未启用、没有资料与基础设施不可用。 */
public enum KnowledgeSearchStatus {
    DISABLED,
    EMPTY,
    UNAVAILABLE,
    FOUND
}
