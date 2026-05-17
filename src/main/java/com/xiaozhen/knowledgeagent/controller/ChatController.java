package com.xiaozhen.knowledgeagent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.xiaozhen.knowledgeagent.common.ApiResponse;
import com.xiaozhen.knowledgeagent.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/ask")
    public ApiResponse<String> ask(@RequestBody AskRequest request) throws JsonProcessingException {
        String answer = chatService.ask(
                request.getSessionId() != null ? request.getSessionId() : "default-session",
                request.getQuestion()
        );
        return ApiResponse.ok(answer);
    }

    public static class AskRequest {
        private String sessionId;
        private String question;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
    }
}