package org.javaup.ai.review.evaluation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.javaup.ai.model.AgentModelProperties;
import org.javaup.ai.model.ConfiguredAgentModelClient;
import org.javaup.ai.model.SpringAICompatibleModel;
import org.javaup.ai.observability.AgentTelemetry;
import org.javaup.ai.review.ReviewClient;
import org.javaup.ai.review.ReviewProperties;
import org.javaup.ai.review.SpringAIReviewClient;
import org.javaup.ai.search.SearchProperties;
import org.javaup.ai.search.SearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/** Explicit manual paid-API opt-in; ordinary builds never execute this method. */
@EnabledIfSystemProperty(named = "agent.review.evaluation.enabled", matches = "true")
class ReviewEvaluationLiveTest {

    @Test
    void evaluateSyntheticCandidatesAgainstConfiguredReviewer() throws Exception {
        if (!"YES".equals(System.getenv("AI_REVIEW_EVAL_ALLOW_PAID_CALLS"))) {
            throw new IllegalStateException("Explicit paid-call consent is required");
        }
        String provider = required("AI_REVIEW_EVAL_PROVIDER");
        String model = required("AI_REVIEW_EVAL_MODEL");
        String baseUrl = required("AI_REVIEW_EVAL_BASE_URL");
        String key = required("AI_REVIEW_EVAL_API_KEY");
        int maxCalls = Integer.parseInt(System.getProperty("agent.review.evaluation.max-calls", "10"));
        if (maxCalls < 1 || maxCalls > 10) {
            throw new IllegalArgumentException("Evaluation max-calls must be between one and ten");
        }
        Path reportPath = workspaceRoot().resolve("output/agent-review-evaluation/review-report.json");
        Files.createDirectories(reportPath.getParent());

        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        ObservationRegistry observations = ObservationRegistry.create();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            AgentTelemetry telemetry = new AgentTelemetry(observations, meters);
            AgentModelProperties models = new AgentModelProperties();
            models.setTimeout(Duration.ofSeconds(45));
            models.setMaxOutputTokens(1500);
            models.setProfiles(new LinkedHashMap<>());
            models.getProfiles().put("evaluation-reviewer",
                    new AgentModelProperties.Profile(provider, model, baseUrl, key));
            SpringAICompatibleModel real = new SpringAICompatibleModel(
                    models, mapper, new SearchTool(new SearchProperties(), mapper), telemetry, observations);
            ConfiguredAgentModelClient configured = new ConfiguredAgentModelClient(models, real);
            ReviewProperties settings = new ReviewProperties();
            settings.setEnabled(true);
            settings.setProvider(provider);
            settings.setModel(model);
            ReviewClient reviewer = new SpringAIReviewClient(settings, configured, real);
            reviewer.requireAvailable("fixture", "synthetic-candidate");

            ReviewEvaluationSupport.EvaluationReport report = ReviewEvaluationSupport.evaluate(
                    ReviewEvaluationSupport.loadCases(mapper), reviewer, maxCalls, provider, model);
            Files.writeString(reportPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
            System.out.println("Review evaluation report: " + reportPath);
            System.out.println("Attempted calls: " + report.summary().attemptedCalls()
                    + "; failed calls: " + report.summary().failedCalls()
                    + "; synthetic verdict agreements: " + report.summary().verdictAgreements());
        } finally {
            meters.close();
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + name);
        }
        return value;
    }

    private Path workspaceRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("backend")) && Files.isDirectory(cursor.resolve("frontend"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate project workspace for the private evaluation report");
    }
}
