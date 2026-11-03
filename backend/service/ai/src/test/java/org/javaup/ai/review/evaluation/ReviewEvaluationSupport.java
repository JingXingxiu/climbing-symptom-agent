package org.javaup.ai.review.evaluation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.model.AgentDecision;
import org.javaup.ai.model.AgentModelRequest;
import org.javaup.ai.review.ReviewClient;
import org.javaup.ai.review.ReviewIssueCode;
import org.javaup.ai.review.ReviewResult;
import org.javaup.ai.review.ReviewVerdict;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed synthetic cases evaluate the reviewer; they do not provide medical ground truth. */
final class ReviewEvaluationSupport {

    private ReviewEvaluationSupport() {
    }

    static List<EvaluationCase> loadCases(ObjectMapper mapper) {
        try (InputStream input = ReviewEvaluationSupport.class.getResourceAsStream(
                "/review-evaluation/cases.json")) {
            if (input == null) {
                throw new IllegalStateException("Review evaluation cases are missing");
            }
            JsonNode root = mapper.readTree(input);
            List<EvaluationCase> cases = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode node : root.path("cases")) {
                EvaluationCase item = mapper.treeToValue(node, EvaluationCase.class);
                if (item.id() == null || !ids.add(item.id())
                        || item.candidate() == null
                        || item.candidate().behavior() != AgentBehavior.PRODUCE_RESULT
                        || item.candidate().outcome() == null
                        || item.expectedVerdicts() == null || item.expectedVerdicts().isEmpty()) {
                    throw new IllegalStateException("Invalid synthetic review case");
                }
                cases.add(item);
            }
            if (cases.isEmpty() || cases.size() > 10) {
                throw new IllegalStateException("Review evaluation needs between one and ten cases");
            }
            return List.copyOf(cases);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load synthetic review cases", failure);
        }
    }

    static EvaluationReport evaluate(
            List<EvaluationCase> cases,
            ReviewClient reviewer,
            int maxCalls,
            String reviewerProvider,
            String reviewerModel
    ) {
        if (maxCalls < 1 || maxCalls > 10) {
            throw new IllegalArgumentException("maxCalls must be between one and ten");
        }
        List<CaseResult> results = new ArrayList<>();
        int accepted = 0;
        int errors = 0;
        int verdictAgreements = 0;
        int expectedIssueCases = 0;
        int issueAgreements = 0;
        long latency = 0;
        long knownTokens = 0;
        int unknownTokenCalls = 0;

        for (EvaluationCase item : cases.subList(0, Math.min(maxCalls, cases.size()))) {
            long started = System.nanoTime();
            ReviewResult review = null;
            String errorCode = null;
            try {
                review = reviewer.review(item.request(), item.candidate());
                if (review == null || review.verdict() == null || review.issues() == null) {
                    throw new IllegalStateException("Incomplete review response");
                }
            } catch (ApiException failure) {
                errorCode = failure.code();
            } catch (RuntimeException failure) {
                errorCode = "EVALUATION_CALL_FAILED";
                review = null;
            }
            long elapsed = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            latency += elapsed;
            boolean verdictMatches = review != null && errorCode == null
                    && item.expectedVerdicts().contains(review.verdict());
            Set<ReviewIssueCode> actualIssues = review == null ? Set.of()
                    : new HashSet<>(review.issues().stream().map(issue -> issue.code()).toList());
            boolean issueMatches = review != null && errorCode == null
                    && actualIssues.containsAll(item.expectedIssueCodes());
            if (errorCode != null) errors++;
            if (review != null && review.verdict() == ReviewVerdict.ACCEPT && errorCode == null) accepted++;
            if (verdictMatches) verdictAgreements++;
            if (!item.expectedIssueCodes().isEmpty()) {
                expectedIssueCases++;
                if (issueMatches) issueAgreements++;
            }
            if (review == null || review.totalTokens() == null) {
                unknownTokenCalls++;
            } else {
                knownTokens += Math.max(0, review.totalTokens());
            }
            results.add(new CaseResult(
                    item.id(), item.description(), item.expectedVerdicts(), item.expectedIssueCodes(),
                    review == null ? null : review.verdict(),
                    actualIssues.stream().sorted().toList(), verdictMatches, issueMatches,
                    elapsed, review == null ? null : review.inputTokens(),
                    review == null ? null : review.outputTokens(),
                    review == null ? null : review.totalTokens(), errorCode));
        }

        return new EvaluationReport(
                Instant.now(), true,
                "Synthetic rubric agreement only; not medical accuracy or production evaluation.",
                reviewerProvider, reviewerModel,
                new Summary(results.size(), errors, accepted, verdictAgreements,
                        expectedIssueCases, issueAgreements, latency, knownTokens, unknownTokenCalls),
                List.copyOf(results));
    }

    record EvaluationCase(
            String id,
            String description,
            JsonNode input,
            JsonNode state,
            AgentDecision candidate,
            List<ReviewVerdict> expectedVerdicts,
            List<ReviewIssueCode> expectedIssueCodes
    ) {
        EvaluationCase {
            expectedIssueCodes = expectedIssueCodes == null ? List.of() : List.copyOf(expectedIssueCodes);
        }

        AgentModelRequest request() {
            return new AgentModelRequest(
                    UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
                    "fixture", "synthetic-candidate", "zh-CN", input, state, List.of(), List.of());
        }
    }

    record CaseResult(
            String caseId,
            String description,
            List<ReviewVerdict> expectedVerdicts,
            List<ReviewIssueCode> expectedIssueCodes,
            ReviewVerdict actualVerdict,
            List<ReviewIssueCode> actualIssueCodes,
            boolean verdictMatches,
            boolean expectedIssuesPresent,
            long latencyMs,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            String errorCode
    ) {}

    record Summary(
            int attemptedCalls,
            int failedCalls,
            int acceptedCases,
            int verdictAgreements,
            int casesWithExpectedIssues,
            int expectedIssueAgreements,
            long totalLatencyMs,
            long knownTotalTokens,
            int callsWithUnknownTokenUsage
    ) {}

    record EvaluationReport(
            Instant generatedAt,
            boolean syntheticCases,
            String interpretation,
            String reviewerProvider,
            String reviewerModel,
            Summary summary,
            List<CaseResult> cases
    ) {}
}
