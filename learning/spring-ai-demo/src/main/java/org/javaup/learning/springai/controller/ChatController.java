package org.javaup.learning.springai.controller;

import jakarta.validation.Valid;
import org.javaup.learning.springai.dto.AgentReply;
import org.javaup.learning.springai.dto.ChatRequest;
import org.javaup.learning.springai.dto.ChatResponseDto;
import org.javaup.learning.springai.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponseDto chat(@Valid @RequestBody ChatRequest request) {
        return new ChatResponseDto(chatService.chat(request.message()));
    }

    @PostMapping("/chat/structured")
    public AgentReply structuredChat(@Valid @RequestBody ChatRequest request) {
        return chatService.structuredChat(request.message());
    }

    @PostMapping("/chat/tools")
    public ChatResponseDto chatWithTools(@Valid @RequestBody ChatRequest request) {
        return new ChatResponseDto(chatService.chatWithTools(request.message()));
    }
}
