package org.javaup.history.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.javaup.history.api.dto.AppendAssessmentAnswerRequest;
import org.javaup.history.api.dto.AssessmentAnswerResponse;
import org.javaup.history.api.dto.AssessmentResultResponse;
import org.javaup.history.api.dto.HistoryEnums.AssessmentAnswerSource;
import org.javaup.history.api.dto.HistoryEnums.RecommendationLevel;
import org.javaup.history.api.dto.HistoryEnums.ResultGeneratedBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PGobject;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AssessmentAnswerAndResultFactoriesTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-25T12:00:00+08:00");

    @Test
    void createsAnswerWithStableClientIdentityAndRoundTripsStructuredChineseContent() {
        UUID assessmentId = UUID.randomUUID();
        UUID clientAnswerId = UUID.randomUUID();
        var json = mapper.readTree("{\"symptoms\":[\"麻\",\"酸\"],\"confirmed\":true}");
        var request = new AppendAssessmentAnswerRequest(clientAnswerId, 5,
                "clarify.location", "具体哪里不舒服？", json, AssessmentAnswerSource.USER_CORRECTION);

        AssessmentAnswer entity = AssessmentAnswer.create(assessmentId, request, now, mapper);
        AssessmentAnswerResponse response = AssessmentAnswerResponse.fromEntity(entity, mapper);

        assertNotNull(entity.getId());
        assertEquals(assessmentId, entity.getAssessmentId());
        assertEquals(clientAnswerId, entity.getClientAnswerId());
        assertEquals(5, entity.getSequenceNo());
        assertEquals("clarify.location", entity.getQuestionKey());
        assertEquals("具体哪里不舒服？", entity.getQuestionText());
        assertEquals("jsonb", entity.getAnswer().getType());
        assertEquals(json, mapper.readTree(entity.getAnswer().getValue()));
        assertEquals("USER_CORRECTION", entity.getAnswerSource());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(entity.getId(), response.answerId());
        assertEquals(clientAnswerId, response.clientAnswerId());
        assertEquals(5, response.sequenceNumber());
        assertEquals(json, response.answer());
        assertEquals(AssessmentAnswerSource.USER_CORRECTION, response.source());
        assertEquals(now.toInstant(), response.createdAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "\"按压时酸\"", "2.5", "false"})
    void acceptsAllNonNullJsonAnswerTypesWithoutConvertingThemToStrings(String rawJson) {
        var request = new AppendAssessmentAnswerRequest(UUID.randomUUID(), 1,
                "question.one", null, mapper.readTree(rawJson), AssessmentAnswerSource.MOCK_AGENT);

        AssessmentAnswer entity = AssessmentAnswer.create(UUID.randomUUID(), request, now, mapper);
        AssessmentAnswerResponse response = AssessmentAnswerResponse.fromEntity(entity, mapper);

        assertEquals(mapper.readTree(rawJson), response.answer());
        assertNull(response.questionText());
    }

    @Test
    void rejectsJsonNullAndMissingAnswersRatherThanWritingInvalidJsonb() {
        for (var answer : List.of(mapper.readTree("null"), mapper.missingNode())) {
            var request = new AppendAssessmentAnswerRequest(UUID.randomUUID(), 1,
                    "question.one", null, answer, AssessmentAnswerSource.INITIAL_FORM);
            assertThrows(IllegalArgumentException.class,
                    () -> AssessmentAnswer.create(UUID.randomUUID(), request, now, mapper));
        }
    }

    @ParameterizedTest
    @EnumSource(ResultGeneratedBy.class)
    void mapsExistingResultWithoutChangingProvenanceSafetyNoticeOrFinalization(ResultGeneratedBy generatedBy)
            throws Exception {
        AssessmentResult entity = result(generatedBy);

        AssessmentResultResponse response = AssessmentResultResponse.fromEntity(entity, mapper);

        assertEquals(entity.getAssessmentId(), response.assessmentId());
        assertEquals(RecommendationLevel.ROUTINE_PROFESSIONAL, response.recommendationLevel());
        assertEquals("已记录右手不适描述", response.summary());
        assertEquals(List.of("用户描述影响正常用手"), response.reasons());
        assertEquals(List.of("携带记录咨询专业人员"), response.suggestedNextActions());
        assertEquals(List.of(), response.warningSigns());
        assertEquals(entity.getSafetyNotice(), response.safetyNotice());
        assertEquals(generatedBy, response.generatedBy());
        assertEquals(now.toInstant(), response.finalizedAt());
        assertThrows(UnsupportedOperationException.class, () -> response.reasons().add("another"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void refusesMissingSafetyNoticeInsteadOfInventingOne(String notice) throws Exception {
        AssessmentResult entity = result(ResultGeneratedBy.MOCK);
        entity.setSafetyNotice(notice);

        assertThrows(IllegalStateException.class, () -> AssessmentResultResponse.fromEntity(entity, mapper));
    }

    @Test
    void refusesMalformedStoredResultListShapesAndNullElements() throws Exception {
        for (String raw : List.of("{}", "null", "[null]", "[123]")) {
            AssessmentResult entity = result(ResultGeneratedBy.MOCK);
            entity.setReasons(jsonb(raw));
            assertThrows(IllegalStateException.class,
                    () -> AssessmentResultResponse.fromEntity(entity, mapper));
        }
    }

    private AssessmentResult result(ResultGeneratedBy generatedBy) throws SQLException {
        AssessmentResult entity = new AssessmentResult();
        entity.setAssessmentId(UUID.randomUUID());
        entity.setRecommendationLevel("ROUTINE_PROFESSIONAL");
        entity.setSummary("已记录右手不适描述");
        entity.setReasons(jsonb("[\"用户描述影响正常用手\"]"));
        entity.setSuggestedNextActions(jsonb("[\"携带记录咨询专业人员\"]"));
        entity.setWarningSigns(jsonb("[]"));
        entity.setSafetyNotice("仅用于整理症状描述，不能替代专业医疗意见。");
        entity.setGeneratedBy(generatedBy.name());
        entity.setFinalizedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private PGobject jsonb(String raw) throws SQLException {
        PGobject value = new PGobject();
        value.setType("jsonb");
        value.setValue(raw);
        return value;
    }
}
