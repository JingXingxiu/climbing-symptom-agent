package org.javaup.ai.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeRetrievalServiceTest {
    private final KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
    private final KnowledgeRetrievalMapper mapper = mock(KnowledgeRetrievalMapper.class);
    private final KnowledgeEmbeddingClient embeddings = mock(KnowledgeEmbeddingClient.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private KnowledgeRetrievalService service;

    @BeforeEach
    void configureRetrieval() {
        properties.setEnabled(true);
        properties.setProvider("local");
        properties.setModel("embedding-test");
        properties.setDimensions(3);
        properties.setBaseUrl("http://127.0.0.1:12345/v1");
        properties.setApiKey("test-key");
        service = new KnowledgeRetrievalService(properties, mapper, embeddings);
    }

    @Test
    void disabledNeverTouchesDatabaseOrEmbedding() {
        properties.setEnabled(false);
        assertEquals(KnowledgeSearchStatus.DISABLED, service.retrieve(json.createObjectNode()).status());
        assertEquals(KnowledgeSearchStatus.DISABLED,
                KnowledgeRetrievalService.disabled().retrieve(null).status());
        verifyNoInteractions(mapper, embeddings);
    }

    @Test
    void emptyDatabaseNeedsNeitherCredentialsNorPaidEmbedding() {
        properties.setProvider(null);
        properties.setModel(null);
        properties.setDimensions(0);
        properties.setApiKey(null);
        when(mapper.hasAnySearchableChunks()).thenReturn(false);
        KnowledgeSearchReport report = service.retrieve(json.createObjectNode());
        assertEquals(KnowledgeSearchStatus.EMPTY, report.status());
        assertTrue(report.references().isEmpty());
        verify(mapper).hasAnySearchableChunks();
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(embeddings);
    }

    @Test
    void missingSchemaIsUnavailableRatherThanEmptyAndLeaksNoException() {
        when(mapper.hasAnySearchableChunks()).thenThrow(
                new DataAccessResourceFailureException("secret SQL jdbc:private password=do-not-leak"));
        KnowledgeSearchReport report = service.retrieve(null);
        assertEquals(KnowledgeSearchStatus.UNAVAILABLE, report.status());
        assertFalse(report.note().contains("secret"));
        assertFalse(report.note().contains("jdbc"));
        verifyNoInteractions(embeddings);
    }

    @Test
    void incompatibleIndexIsEmptyAndDoesNotGenerateQueryEmbedding() {
        when(mapper.hasAnySearchableChunks()).thenReturn(true);
        when(mapper.hasCompatibleChunks("local:embedding-test", 3)).thenReturn(false);
        assertEquals(KnowledgeSearchStatus.EMPTY, service.retrieve(null).status());
        verifyNoInteractions(embeddings);
    }

    @Test
    void existingIndexWithMissingConfigurationIsUnavailable() {
        when(mapper.hasAnySearchableChunks()).thenReturn(true);
        properties.setDimensions(0);
        assertEquals(KnowledgeSearchStatus.UNAVAILABLE, service.retrieve(null).status());
        verifyNoInteractions(embeddings);
    }

    @Test
    void foundReferencesAreBoundedAndEmbeddingReceivesOnlyPublicEnums() {
        matchingIndex();
        when(embeddings.embed(anyString())).thenReturn(new float[] {1, 0, 0});
        UUID document = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        when(mapper.nearest(eq("local:embedding-test"), eq(3), eq("[1.0,0.0,0.0]"),
                anyDouble(), eq(3), eq(1600)))
                .thenReturn(List.of(new KnowledgeReference(
                        document, chunk, "公开参考", null, "reviewed", "x".repeat(5000), 0.9)));
        var input = json.readTree("""
                {"userId":"private-user","pictureUrl":"https://private.invalid/?signature=secret",
                  "assessment":{"initialInput":{"symptomTypes":["NUMBNESS"],"notes":"私密描述"},
                    "symptomLocations":[{"fingerCode":"MIDDLE","jointCode":"PIP"}]}}
                """);
        KnowledgeSearchReport report = service.retrieve(input);
        assertEquals(KnowledgeSearchStatus.FOUND, report.status());
        assertEquals(1, report.references().size());
        assertEquals(document, report.references().getFirst().documentId());
        assertEquals(chunk, report.references().getFirst().chunkId());
        assertNull(report.references().getFirst().sourceUri());
        assertEquals(1600, report.references().getFirst().content().length());
        verify(embeddings).embed("攀岩 手部 症状 运动医学 麻木 中指 近端指间关节");
    }

    @Test
    void invalidDimensionsAndNonFiniteOrZeroVectorsNeverReachDatabaseDistanceQuery() {
        matchingIndex();
        for (float[] invalid : List.of(new float[] {1}, new float[] {Float.NaN, 0, 1},
                new float[] {0, 0, 0}, new float[] {Float.POSITIVE_INFINITY, 0, 1})) {
            when(embeddings.embed(anyString())).thenReturn(invalid);
            assertEquals(KnowledgeSearchStatus.UNAVAILABLE, service.retrieve(null).status());
        }
        verify(mapper, never()).nearest(anyString(), anyInt(), anyString(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void similarityMissAndPostEmbeddingDatabaseFailureHaveDifferentStatuses() {
        matchingIndex();
        when(embeddings.embed(anyString())).thenReturn(new float[] {1, 0, 0});
        when(mapper.nearest(anyString(), anyInt(), anyString(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(List.of());
        assertEquals(KnowledgeSearchStatus.EMPTY, service.retrieve(null).status());
        when(mapper.nearest(anyString(), anyInt(), anyString(), anyDouble(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("do-not-leak"));
        assertEquals(KnowledgeSearchStatus.UNAVAILABLE, service.retrieve(null).status());
    }

    @Test
    void unsafeSupplierEndpointIsRejectedBeforeEmbedding() {
        matchingIndex();
        properties.setBaseUrl("http://remote-private.invalid/v1?api-key=secret");
        assertEquals(KnowledgeSearchStatus.UNAVAILABLE, service.retrieve(null).status());
        verifyNoInteractions(embeddings);
    }

    private void matchingIndex() {
        when(mapper.hasAnySearchableChunks()).thenReturn(true);
        when(mapper.hasCompatibleChunks("local:embedding-test", 3)).thenReturn(true);
    }
}
