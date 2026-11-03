package org.javaup.ai.model;

import static org.javaup.ai.api.problem.AIErrorCode.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentQuestion;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.observability.AgentTelemetry;
import org.javaup.ai.rag.KnowledgeRetrievalService;
import org.javaup.ai.rag.KnowledgeSearchReport;
import org.javaup.ai.search.SearchTool;
import org.javaup.ai.review.ReviewIssue;
import org.javaup.ai.review.ReviewResult;
import org.javaup.ai.review.ReviewVerdict;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;

/**
 * 识图、资料检索、单次模型请求、结果校验的适配实现。
 * 不持有数据库事务，不启用自动工具循环/重试；调用次数可由应用层准确预占。
 */
@Component
public class SpringAICompatibleModel {

    private final AgentModelProperties properties;
    private final ObjectMapper mapper;
    private final SearchTool search;
    private final AgentTelemetry telemetry;
    private final ObservationRegistry observationRegistry;
    private final KnowledgeRetrievalService knowledge;
    private final String systemInstruction;
    private final String reviewInstruction;
    private final String correctionInstruction;
    private final Map<String, ChatModel> clients = new ConcurrentHashMap<>();

    @Autowired
    public SpringAICompatibleModel(
            AgentModelProperties properties,
            ObjectMapper mapper,
            SearchTool search,
            AgentTelemetry telemetry,
            ObservationRegistry observationRegistry,
            KnowledgeRetrievalService knowledge
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.search = search;
        this.telemetry = telemetry;
        this.observationRegistry = observationRegistry;
        this.knowledge = knowledge;

        try {
            this.systemInstruction =
                    new ClassPathResource("prompts/symptom-agent.md")
                            .getContentAsString(StandardCharsets.UTF_8);
            this.reviewInstruction = new ClassPathResource("prompts/symptom-review.md")
                    .getContentAsString(StandardCharsets.UTF_8);
            this.correctionInstruction = new ClassPathResource("prompts/symptom-correction.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load symptom-agent prompt resource", failure);
        }
    }

    /** 独立模型适配测试可不创建数据库；生产构造始终注入真实检索服务。 */
    public SpringAICompatibleModel(
            AgentModelProperties properties,
            ObjectMapper mapper,
            SearchTool search,
            AgentTelemetry telemetry,
            ObservationRegistry observationRegistry
    ) {
        this(properties, mapper, search, telemetry, observationRegistry, KnowledgeRetrievalService.disabled());
    }

    public AgentModelResult analyze(
            AgentModelRequest request,
            AgentModelProperties.Profile profile
    ) {
        KnowledgeSearchReport knowledgeReport = telemetry.observe("knowledge_retrieval", () -> {
            KnowledgeSearchReport result = knowledge.retrieve(request.inputSnapshot());
            Observation current = observationRegistry.getCurrentObservation();

            if (current != null) {
                current.lowCardinalityKeyValue("status", result.status().name().toLowerCase(Locale.ROOT));
                current.highCardinalityKeyValue("source.count", Integer.toString(result.references().size()));
            }

            return result;
        });
        // 每次最多检索一次；失败明确报告，不伪装为有证据的联网结果。
        SearchTool.SearchReport report = telemetry.observe("web_search", () -> {
            SearchTool.SearchReport result = search.search(search.queryFor(request.inputSnapshot()));
            Observation current = observationRegistry.getCurrentObservation();

            if (current != null) {
                String status = !result.available() ? "unavailable"
                        : result.references().isEmpty() ? "empty" : "available";
                current.lowCardinalityKeyValue("status", status);
                current.highCardinalityKeyValue("source.count", Integer.toString(result.references().size()));
            }

            return result;
        });
        String body = mapper.writeValueAsString(Map.of(
                "locale", request.locale(),
                "initialInput", request.inputSnapshot(),
                "currentState", request.state(),
                "activeBranch", request.history(),
                "knowledge", Map.of(
                        "status", knowledgeReport.status(),
                        "note", knowledgeReport.note(),
                        "references", knowledgeSources(knowledgeReport)),
                "search", report));

        return decision(
                request, profile, body, systemInstruction,
                verifiedSources(report, knowledgeReport), false, "model_request");
    }

    /** Exactly one correction, using the frozen source catalogue; retrieval is not repeated. */
    public AgentModelResult revise(
            AgentModelRequest request,
            AgentDecision candidate,
            List<ReviewIssue> issues,
            AgentModelProperties.Profile profile
    ) {
        requireFinalCandidate(candidate);
        if (issues == null || issues.isEmpty() || issues.size() > 5) {
            throw invalidReviewOutput();
        }
        String body = mapper.writeValueAsString(Map.of(
                "locale", request.locale(),
                "initialInput", request.inputSnapshot(),
                "currentState", request.state(),
                "activeBranch", request.history(),
                "candidate", candidate,
                "reviewIssues", issues,
                "verifiedReferences", references(candidate)));
        return decision(
                request,
                profile,
                body,
                systemInstruction + "\n" + correctionInstruction,
                references(candidate),
                true,
                "revision_model_request");
    }

    /** Reviewer sees the same input and photographs, not only the primary model's summary. */
    public ReviewResult review(
            AgentModelRequest request,
            AgentDecision candidate,
            AgentModelProperties.Profile profile
    ) {
        requireFinalCandidate(candidate);
        BeanOutputConverter<ReviewDecision> converter = new BeanOutputConverter<>(ReviewDecision.class);
        String body = mapper.writeValueAsString(Map.of(
                "locale", request.locale(),
                "initialInput", request.inputSnapshot(),
                "currentState", request.state(),
                "activeBranch", request.history(),
                "candidate", candidate,
                "verifiedReferences", references(candidate)));
        ChatResponse response = call(
                request, profile, body, reviewInstruction + "\n" + converter.getFormat(), "review_model_request");
        ResponseUsage usage = recordUsage(profile, response);
        ReviewDecision decision = telemetry.observe("review_output_validation", () -> {
            try {
                String raw = outputText(response);
                if (raw.length() > 10_000) {
                    throw invalidReviewOutput();
                }
                JsonNode node = mapper.readerFor(JsonNode.class)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .readValue(raw);
                if (!node.isObject() || node.size() != 2 || !node.has("verdict") || !node.has("issues")
                        || !node.path("verdict").isTextual() || !node.path("issues").isArray()) {
                    throw invalidReviewOutput();
                }
                for (var issue : node.path("issues")) {
                    if (!issue.isObject() || issue.size() != 2 || !issue.path("code").isTextual()
                            || !issue.path("reason").isTextual()) {
                        throw invalidReviewOutput();
                    }
                }
                ReviewDecision parsed = converter.convert(raw);
                validateReview(parsed);
                return parsed;
            } catch (RuntimeException invalid) {
                throw invalidReviewOutput();
            }
        });
        return new ReviewResult(
                decision.verdict(), decision.issues(), usage.id(),
                usage.input(), usage.output(), usage.total());
    }

    private AgentModelResult decision(
            AgentModelRequest request,
            AgentModelProperties.Profile profile,
            String body,
            String instruction,
            List<AgentDecision.Reference> verifiedSources,
            boolean finalOnly,
            String stage
    ) {
        BeanOutputConverter<AgentDecision> converter = new BeanOutputConverter<>(AgentDecision.class);
        ChatResponse response = call(request, profile, body, instruction + "\n" + converter.getFormat(), stage);
        ResponseUsage usage = recordUsage(profile, response);
        AgentDecision decision = telemetry.observe("output_validation", () -> {
            try {
                AgentDecision parsed = validate(converter.convert(outputText(response)), verifiedSources);
                if (finalOnly && parsed.behavior() != AgentBehavior.PRODUCE_RESULT) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY,
                            INVALID_MODEL_REVISION, "The correction did not return a final candidate.");
                }
                return parsed;
            } catch (ApiException failure) {
                throw failure;
            } catch (RuntimeException malformed) {
                throw invalidOutput();
            }
        });
        return new AgentModelResult(
                decision, usage.id(), usage.input(), usage.output(), usage.total());
    }

    private ChatResponse call(
            AgentModelRequest request,
            AgentModelProperties.Profile profile,
            String body,
            String instruction,
            String stage
    ) {
        if (body.length() > properties.getMaxInputChars()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    MODEL_CONTEXT_TOO_LARGE,
                    "The active branch exceeds the configured model input limit.");
        }

        UserMessage user = UserMessage.builder()
                .text(body)
                .media(telemetry.observe("image_download", () -> loadPictures(request.pictures())))
                .build();
        return telemetry.observe(
                stage,
                Map.of("provider", profile.getProvider(), "model", profile.getModel()),
                () -> {
                    try {
                        return model(profile.getProvider(), profile)
                                .call(new Prompt(List.of(new SystemMessage(instruction), user)));
                    } catch (RuntimeException failure) {
                        throw new ApiException(
                                HttpStatus.BAD_GATEWAY,
                                "review_model_request".equals(stage) ? QUALITY_REVIEW_CALL_FAILED : MODEL_CALL_FAILED,
                                "The selected model request failed; no result was accepted.");
                    }
                });
    }

    private ResponseUsage recordUsage(AgentModelProperties.Profile profile, ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        Integer inputTokens = usage == null ? null : usage.getPromptTokens();
        Integer outputTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();
        // 输出校验失败也可能已经消耗token；在业务结果校验前记录平台提供的用量。
        telemetry.recordTokens(profile.getProvider(), profile.getModel(), inputTokens, outputTokens);
        return new ResponseUsage(
                response == null || response.getMetadata() == null ? null : response.getMetadata().getId(),
                inputTokens, outputTokens, totalTokens);
    }

    private static String outputText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw invalidOutput();
        }
        return response.getResult().getOutput().getText();
    }

    private static List<AgentDecision.Reference> references(AgentDecision candidate) {
        return candidate.references() == null ? List.of() : List.copyOf(candidate.references());
    }

    private static void requireFinalCandidate(AgentDecision candidate) {
        if (candidate == null || candidate.behavior() != AgentBehavior.PRODUCE_RESULT || candidate.outcome() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    INVALID_REVIEW_CANDIDATE, "Only a final result candidate can be reviewed or corrected.");
        }
    }

    private static void validateReview(ReviewDecision decision) {
        if (decision == null || decision.verdict() == null || decision.issues() == null || decision.issues().size() > 5
                || (decision.verdict() == ReviewVerdict.ACCEPT) != decision.issues().isEmpty()) {
            throw invalidReviewOutput();
        }
        Set<org.javaup.ai.review.ReviewIssueCode> codes = new HashSet<>();
        for (ReviewIssue issue : decision.issues()) {
            if (issue == null || issue.code() == null || issue.reason() == null || issue.reason().isBlank()
                    || issue.reason().length() > 500 || !codes.add(issue.code())) {
                throw invalidReviewOutput();
            }
        }
    }

    private static ApiException invalidReviewOutput() {
        return new ApiException(HttpStatus.BAD_GATEWAY,
                INVALID_QUALITY_REVIEW_OUTPUT, "The reviewer did not return a valid bounded review decision.");
    }

    public record ReviewDecision(ReviewVerdict verdict, List<ReviewIssue> issues) {}

    private record ResponseUsage(String id, Integer input, Integer output, Integer total) {}

    /** 来源来自实际工具响应；知识资料没有公网 URL 时仍保留文档和片段 ID。 */
    private List<AgentDecision.Reference> verifiedSources(
            SearchTool.SearchReport searchReport,
            KnowledgeSearchReport knowledgeReport
    ) {
        List<AgentDecision.Reference> references = new ArrayList<>(searchReport.references());
        references.addAll(knowledgeSources(knowledgeReport));
        return List.copyOf(references);
    }

    private List<AgentDecision.Reference> knowledgeSources(KnowledgeSearchReport knowledgeReport) {
        return knowledgeReport.references().stream().map(source -> new AgentDecision.Reference(
                source.title(),
                publicSourceUrl(source.sourceUri()),
                source.content(),
                "KNOWLEDGE",
                source.documentId(),
                source.chunkId(),
                source.contentVersion())).toList();
    }

    private String publicSourceUrl(String sourceUri) {
        if (sourceUri == null || sourceUri.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(sourceUri);
            return List.of("http", "https").contains(uri.getScheme())
                    && uri.getHost() != null && uri.getUserInfo() == null
                    ? sourceUri : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private ChatModel model(
            String provider,
            AgentModelProperties.Profile profile
    ) {
        return clients.computeIfAbsent(provider + ":" + profile.getModel(), key -> {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .baseUrl(profile.getBaseUrl())
                    .apiKey(profile.getApiKey())
                    .model(profile.getModel())
                    .maxRetries(0)
                    .timeout(properties.getTimeout())
                    .maxTokens(properties.getMaxOutputTokens())
                    .extraBody(switch (provider) {
                        case "qwen" -> Map.of("enable_thinking", false);
                        case "zhipu" -> Map.of("thinking", Map.of("type", "disabled"));
                        default -> Map.of();
                    })
                    .build();

            return OpenAiChatModel.builder()
                    .options(options)
                    .observationRegistry(observationRegistry)
                    .build();
        });
    }

    /** URL只能来自image-service；下载后以图片字节提交，云端模型不必访问本机MinIO。 */
    private List<Media> loadPictures(List<AgentModelRequest.Picture> pictures) {
        if (pictures == null || pictures.isEmpty()) {
            return List.of();
        }

        if (pictures.size() > 12) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    TOO_MANY_MODEL_PICTURES,
                    "At most 12 pictures may be included.");
        }

        List<Media> media = new ArrayList<>();
        int total = 0;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        for (AgentModelRequest.Picture picture : pictures) {
            URI url = picture.url();

            if (url == null
                    || url.getHost() == null
                    || !List.of("http", "https").contains(url.getScheme())
                    || properties.getAllowedImageHosts().stream()
                            .noneMatch(host -> host.equalsIgnoreCase(url.getHost()))) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        MODEL_IMAGE_HOST_NOT_ALLOWED,
                        "Picture delivery host is not configured for server-side model input.");
            }

            if (!List.of("image/png", "image/jpeg", "image/webp").contains(picture.mediaType())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        MODEL_IMAGE_TYPE_NOT_ALLOWED,
                        "Unsupported model image type.");
            }

            try {
                HttpRequest request = HttpRequest.newBuilder(url)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                try (InputStream stream = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Picture is unavailable");
                    }

                    int maxBytes = Math.max(
                            1024,
                            Math.min(properties.getMaxImageBytes(), 10 * 1024 * 1024));
                    byte[] bytes = stream.readNBytes(maxBytes + 1);
                    total = Math.addExact(total, bytes.length);

                    if (bytes.length > maxBytes || total > properties.getMaxTotalImageBytes()) {
                        throw new ApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                MODEL_IMAGE_TOO_LARGE,
                                "Pictures exceed the configured model input byte limit.");
                    }

                    media.add(new Media(
                            MimeTypeUtils.parseMimeType(picture.mediaType()),
                            new ByteArrayResource(bytes)));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        MODEL_IMAGE_UNAVAILABLE,
                        "Picture loading was interrupted.");
            } catch (ApiException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        MODEL_IMAGE_UNAVAILABLE,
                        "Picture input could not be loaded.");
            }
        }

        return List.copyOf(media);
    }

    private AgentDecision validate(
            AgentDecision decision,
            List<AgentDecision.Reference> verifiedSources
    ) {
        if (decision == null
                || decision.behavior() == null
                || decision.message() == null
                || decision.message().isBlank()
                || decision.message().length() > 2000
                || decision.extractedFacts() == null
                || !decision.extractedFacts().isObject()
                || decision.contradictionCodes() == null
                || decision.contradictionCodes().size() > 20) {
            throw invalidOutput();
        }

        AgentQuestion question = decision.question();

        if (decision.behavior() == AgentBehavior.PRODUCE_RESULT) {
            var outcome = decision.outcome();

            if (question != null
                    || outcome == null
                    || outcome.recommendationLevel() == null
                    || outcome.summary() == null
                    || outcome.summary().isBlank()
                    || outcome.summary().length() > 4000
                    || !validList(outcome.reasons())
                    || !validList(outcome.suggestedNextActions())
                    || !validList(outcome.warningSigns())) {
                throw invalidOutput();
            }
        } else {
            if (decision.outcome() != null
                    || question == null
                    || question.questionKey() == null
                    || !question.questionKey().matches("^[a-z][a-z0-9_.-]{1,79}$")
                    || question.answerType() == null
                    || question.prompt() == null
                    || question.prompt().isBlank()
                    || question.prompt().length() > 500
                    || (question.options() != null && question.options().size() > 20)) {
                throw invalidOutput();
            }

            question = new AgentQuestion(
                    question.questionId() == null ? UUID.randomUUID() : question.questionId(),
                    question.questionKey(),
                    question.prompt(),
                    question.helpText(),
                    question.answerType(),
                    question.options() == null ? List.of() : question.options(),
                    question.required());
        }

        // 来源由本轮工具结果重建，不接受模型生成的地址、文档或片段 ID。
        return new AgentDecision(
                decision.behavior(),
                decision.message(),
                question,
                decision.extractedFacts(),
                List.copyOf(decision.contradictionCodes()),
                decision.outcome(),
                verifiedSources);
    }

    private static boolean validList(List<String> values) {
        return values != null
                && values.size() <= 20
                && values.stream()
                        .allMatch(value -> value != null
                                && !value.isBlank()
                                && value.length() <= 500);
    }

    private static ApiException invalidOutput() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                INVALID_MODEL_OUTPUT,
                "The model did not return a valid bounded workflow decision.");
    }
}
