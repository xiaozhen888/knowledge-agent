package com.xiaozhen.knowledgeagent.controller;

import com.xiaozhen.knowledgeagent.common.ApiResponse;
import com.xiaozhen.knowledgeagent.service.HotQuestionCacheService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/hotqa")
@RequiredArgsConstructor
public class HotQuestionAdminController {

    private final HotQuestionCacheService hotCache;

    /**
     * 手动添加热点问题
     */
    @PostMapping
    public ApiResponse<String> add(@RequestBody HotQuestionRequest req) {
        hotCache.addHotQuestion(req.getQuestion(), req.getAnswer(), req.getSources());
        return ApiResponse.ok("添加成功");
    }
    /**
     * 删除热点问题
     */
    @DeleteMapping
    public ApiResponse<String> remove(@RequestParam String question) {
        hotCache.removeHotQuestion(question);
        return ApiResponse.ok("删除成功");
    }

    /**
     * 列出当前所有热点问题
     */
    @GetMapping("/list")
    public ApiResponse<List<String>> list() {
        return ApiResponse.ok(hotCache.listHotQuestions());
    }
    /**
     * 查看缓存命中率统计
     */
    @GetMapping("/stats")
    public ApiResponse<String> stats() {
        return ApiResponse.ok(hotCache.getStats());
    }
    /**
     * 请求体 DTO
     */
    @Data
    public static class HotQuestionRequest {
        private String question;
        private String answer;
        private List<String> sources;
    }
}