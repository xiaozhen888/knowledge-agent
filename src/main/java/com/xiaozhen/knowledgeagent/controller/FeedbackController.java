package com.xiaozhen.knowledgeagent.controller;

import com.xiaozhen.knowledgeagent.common.ApiResponse;
import com.xiaozhen.knowledgeagent.model.Feedback;
import com.xiaozhen.knowledgeagent.repository.FeedbackRepository;
import com.xiaozhen.knowledgeagent.service.HotQuestionCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackRepository feedbackRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final HotQuestionCacheService hotQuestionCacheService;

    @PostMapping
    public ApiResponse<String> submit(@RequestBody FeedbackRequest request) {
        Feedback feedback = new Feedback(
                request.getSessionId(),
                request.getQuestion(),
                request.getAnswer(),
                request.getRating()
        );
        feedbackRepository.save(feedback);

        if ("dislike".equals(request.getRating())) {
            String question = request.getQuestion().trim();
            String sessionId = request.getSessionId();

            String rerankKey = "rerank:" + question;
            Boolean deletedRerank = redisTemplate.delete(rerankKey);
            if (Boolean.TRUE.equals(deletedRerank)) {
                System.out.println("用户点踩，已清除精排缓存: " + rerankKey);
            }

            String rewriteHash = String.valueOf(question.hashCode());
            String embedKey = "embedding:query:" + sessionId + ":" + rewriteHash;
            redisTemplate.delete(embedKey);

            hotQuestionCacheService.removeHotQuestion(question);
            System.out.println("用户点踩，已清除热点缓存: " + question);

            redisTemplate.opsForValue().set(
                    "feedback:dislike:" + sessionId,
                    "用户对问题「" + question + "」的回答不满意。请重新思考，调整回答方式，或检查参考内容是否有遗漏。",
                    30, TimeUnit.MINUTES
            );
        }

        return ApiResponse.ok("反馈已提交");
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> stats() {
        long likes = feedbackRepository.countByRating("like");
        long dislikes = feedbackRepository.countByRating("dislike");
        return ApiResponse.ok(Map.of("likes", likes, "dislikes", dislikes));
    }

    public static class FeedbackRequest {
        private String sessionId;
        private String question;
        private String answer;
        private String rating;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public String getRating() { return rating; }
        public void setRating(String rating) { this.rating = rating; }
    }
}