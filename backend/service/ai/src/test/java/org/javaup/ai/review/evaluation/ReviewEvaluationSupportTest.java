package org.javaup.ai.review.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.review.ReviewClient;
import org.javaup.ai.review.ReviewIssue;
import org.javaup.ai.review.ReviewIssueCode;
import org.javaup.ai.review.ReviewResult;
import org.javaup.ai.review.ReviewVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class ReviewEvaluationSupportTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void fixedCasesAreSyntheticAndCoverBothAcceptableAndProblematicResults() {
        List<ReviewEvaluationSupport.EvaluationCase> cases = ReviewEvaluationSupport.loadCases(mapper);

        assertThat(cases).hasSize(10);
        assertThat(cases).extracting(ReviewEvaluationSupport.EvaluationCase::id).doesNotHaveDuplicates();
        assertThat(cases).anySatisfy(item -> assertThat(item.expectedVerdicts()).contains(ReviewVerdict.ACCEPT));
        assertThat(cases).anySatisfy(item -> assertThat(item.expectedIssueCodes()).contains(ReviewIssueCode.UNSAFE_ADVICE));
        assertThat(cases).allSatisfy(item -> {
            assertThat(item.request().pictures()).isEmpty();
            assertThat(item.request().provider()).isEqualTo("fixture");
        });
    }

    @Test
    void failedCallsAndUnknownUsageAreNotCountedAsApprovalsOrZeroCost() {
        ReviewClient reviewer = mock(ReviewClient.class);
        when(reviewer.review(any(), any()))
                .thenReturn(new ReviewResult(ReviewVerdict.ACCEPT, List.of(), "fixture", 10, 5, 15))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "REVIEW_UNAVAILABLE", "PRIVATE_ERROR_BODY"))
                .thenReturn(new ReviewResult(ReviewVerdict.REVISE,
                        List.of(new ReviewIssue(ReviewIssueCode.INPUT_MISMATCH, "Wrong hand")),
                        "fixture", null, null, null));

        ReviewEvaluationSupport.EvaluationReport report = ReviewEvaluationSupport.evaluate(
                ReviewEvaluationSupport.loadCases(mapper), reviewer, 3, "fixture", "reviewer");

        assertThat(report.summary().attemptedCalls()).isEqualTo(3);
        assertThat(report.summary().failedCalls()).isEqualTo(1);
        assertThat(report.summary().acceptedCases()).isEqualTo(1);
        assertThat(report.summary().knownTotalTokens()).isEqualTo(15);
        assertThat(report.summary().callsWithUnknownTokenUsage()).isEqualTo(2);
        assertThat(report.cases().get(1).errorCode()).isEqualTo("REVIEW_UNAVAILABLE");
        assertThat(mapper.writeValueAsString(report)).doesNotContain("PRIVATE_ERROR_BODY");
        verify(reviewer, times(3)).review(any(), any());
    }

    @Test
    void matchingVerdictDoesNotHideMissingExpectedIssueAndReportRoundTrips() {
        ReviewClient reviewer = mock(ReviewClient.class);
        when(reviewer.review(any(), any())).thenReturn(new ReviewResult(
                ReviewVerdict.REVISE,
                List.of(new ReviewIssue(ReviewIssueCode.OTHER, "Different concern")),
                "fixture", 5, 5, 10));
        List<ReviewEvaluationSupport.EvaluationCase> cases = ReviewEvaluationSupport.loadCases(mapper);
        ReviewEvaluationSupport.EvaluationReport report = ReviewEvaluationSupport.evaluate(
                List.of(cases.get(2)), reviewer, 1, "fixture", "reviewer");

        assertThat(report.summary().verdictAgreements()).isEqualTo(1);
        assertThat(report.summary().expectedIssueAgreements()).isZero();
        String json = mapper.writeValueAsString(report);
        ReviewEvaluationSupport.EvaluationReport restored = mapper.readValue(
                json, ReviewEvaluationSupport.EvaluationReport.class);
        assertThat(restored).isEqualTo(report);
        assertThat(restored.interpretation()).contains("not medical accuracy");
    }
}
