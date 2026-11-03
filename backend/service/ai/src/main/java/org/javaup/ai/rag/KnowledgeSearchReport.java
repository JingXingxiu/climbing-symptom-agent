package org.javaup.ai.rag;

import java.util.List;

public record KnowledgeSearchReport(
        KnowledgeSearchStatus status,
        String note,
        List<KnowledgeReference> references
) {
    public KnowledgeSearchReport {
        references = references == null ? List.of() : List.copyOf(references);
    }
}
