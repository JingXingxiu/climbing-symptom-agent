package org.javaup.ai.rag;

import java.util.UUID;

/** 可信检索产生的引用身份；正文是资料片段，不是可执行指令。 */
public record KnowledgeReference(
        UUID documentId,
        UUID chunkId,
        String title,
        String sourceUri,
        String contentVersion,
        String content,
        double score
) {}
