package com.xiaozhen.knowledgeagent.controller;

import com.xiaozhen.knowledgeagent.service.ChatService;
import com.xiaozhen.knowledgeagent.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final RedisTemplate<String, String> redisTemplate;

    @GetMapping("/status/{docId}")
    public String getStatus(@PathVariable String docId) {
        String status = redisTemplate.opsForValue().get("doc:status:" + docId);
        return status != null ? status : "未找到该文档";
    }
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {
        return documentService.processDocument(file);
    }
}