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

import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentQuestion;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.search.SearchTool;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 识图、资料检索、单次模型请求、结果校验的适配实现。
 * 不持有数据库事务，不启用自动工具循环/重试；调用次数可由应用层准确预占。
 */
@Component
public class SpringAICompatibleModel {

    private final AgentModelProperties properties;
    private final ObjectMapper mapper;
    private final SearchTool search;
    private final String systemInstruction;
    private final Map<String, ChatModel> clients = new ConcurrentHashMap<>();

    public SpringAICompatibleModel(
            AgentModelProperties properties,
            ObjectMapper mapper,
            SearchTool search
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.search = search;

        try {
            this.systemInstruction =
                    new ClassPathResource("prompts/symptom-agent.md")
                            .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load symptom-agent prompt resource", failure);
        }
    }

    public AgentModelResult analyze(
            AgentModelRequest request,
            AgentModelProperties.Profile profile
    ) {
        // 每次最多检索一次；失败明确报告，不伪装为有证据的联网结果。
        SearchTool.SearchReport report = search.search(search.queryFor(request.inputSnapshot()));
        BeanOutputConverter<AgentDecision> converter = new BeanOutputConverter<>(AgentDecision.class);
        String body = mapper.writeValueAsString(Map.of(
                "locale", request.locale(),
                "initialInput", request.inputSnapshot(),
                "currentState", request.state(),
                "activeBranch", request.history(),
                "search", report));

        if (body.length() > properties.getMaxInputChars()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    MODEL_CONTEXT_TOO_LARGE,
                    "The active branch exceeds the configured model input limit.");
        }

        UserMessage user = UserMessage.builder()
                .text(body)
                .media(loadPictures(request.pictures()))
                .build();
        String instruction = systemInstruction + "\n" + converter.getFormat();
        ChatResponse response;

        try {
            response =
                    model(request.provider(), profile)
                            .call(new Prompt(List.of(new SystemMessage(instruction), user)));
        } catch (RuntimeException failure) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    MODEL_CALL_FAILED,
                    "The selected model request failed; no result was accepted.");
        }

        if (response.getResult() == null || response.getResult().getOutput() == null) {
            throw invalidOutput();
        }

        AgentDecision decision;

        try {
            decision = validate(
                    converter.convert(Objects.requireNonNull(response.getResult().getOutput().getText())),
                    report.references());
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw invalidOutput();
        }

        Usage usage = response.getMetadata().getUsage();

        return new AgentModelResult(
                decision,
                response.getMetadata().getId(),
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens());
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

            return OpenAiChatModel.builder().options(options).build();
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

        // 来源只能来自本轮真正获取的检索响应；忽略模型自行编造的URL。
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
