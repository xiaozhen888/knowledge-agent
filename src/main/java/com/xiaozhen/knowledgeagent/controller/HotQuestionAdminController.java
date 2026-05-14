package com.xiaozhen.knowledgeagent.controller;

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
    public ResponseEntity<String> add(@RequestBody HotQuestionRequest req) {
        hotCache.addHotQuestion(req.getQuestion(), req.getAnswer(), req.getSources());
        return ResponseEntity.ok("添加成功");
    }

    /**
     * 删除热点问题
     */
    @DeleteMapping
    public ResponseEntity<String> remove(@RequestParam String question) {
        hotCache.removeHotQuestion(question);
        return ResponseEntity.ok("删除成功");
    }

    /**
     * 列出当前所有热点问题
     */
    @GetMapping("/list")
    public ResponseEntity<List<String>> list() {
        return ResponseEntity.ok(hotCache.listHotQuestions());
    }

    /**
     * 查看缓存命中率统计
     */
    @GetMapping("/stats")
    public ResponseEntity<String> stats() {
        return ResponseEntity.ok(hotCache.getStats());
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