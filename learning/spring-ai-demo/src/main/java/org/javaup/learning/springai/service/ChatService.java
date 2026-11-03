package org.javaup.learning.springai.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import lombok.extern.log4j.Log4j2;
import org.javaup.learning.springai.dto.AgentReply;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;

@Log4j2
@Service
public class ChatService {

    private final ChatModel chatModel;
    private final String structuredInstruction;
    private final BeanOutputConverter<AgentReply> replyConverter =
            new BeanOutputConverter<>(AgentReply.class);

    public ChatService(ChatModel chatModel) throws IOException {
        this.chatModel = chatModel;
        this.structuredInstruction = new ClassPathResource("prompts/structured-chat.md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public String chat(String message) {
        UserMessage userMessage = new UserMessage(message);
        Prompt prompt = new Prompt(userMessage);
        ChatResponse response = callModel(prompt);

        return Objects.requireNonNull(response.getResult()).getOutput().getText();
    }

    public AgentReply structuredChat(String message) {
        String instruction = structuredInstruction + "\n" + replyConverter.getFormat();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(instruction),
                new UserMessage(message)
        ));

        ChatResponse response = callModel(prompt);
        String text = Objects.requireNonNull(response.getResult()).getOutput().getText();

        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型未返回可解析的文本");
        }

        AgentReply reply;

        try {
            reply = replyConverter.convert(text);
        } catch (JacksonException malformed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "模型回复无法转换成 AgentReply JSON",
                    malformed
            );
        }

        // convert 负责反序列化，不会替我们检查缺失字段或业务含义。
        if (reply.action() == null || reply.message() == null || reply.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型回复缺少 action 或 message");
        }

        return reply;
    }

    public String chatWithTools(String message) {
        // TODO(owner: learner): 为 DemoTools.currentTime 添加 @Tool，并通过 ChatClient 注册。
        // Spring AI 2.0.2 的工具执行流程使用 ChatClient；下一课在这里接入。

        return ChatClient.create(chatModel)
                .prompt()
                .user(message)
                .tools(new DemoTools())
                .call()
                .content();
    }

    private ChatResponse callModel(Prompt prompt) {
        long startedAt = System.nanoTime();

        try {
            ChatResponse response = chatModel.call(prompt);
            log.info("模型调用成功，tokenUsage={}", response.getMetadata().getUsage());
            return response;
        } finally {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("模型调用耗时={}ms", elapsedMillis);
        }
    }
}
