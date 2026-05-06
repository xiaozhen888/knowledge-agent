package com.xiaozhen.knowledgeagent.controller;

import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import com.xiaozhen.knowledgeagent.service.ChatService;
import com.xiaozhen.knowledgeagent.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final RedisTemplate<String, String> redisTemplate;
    private final DocumentRepository documentRepository;

    @GetMapping("/status/{docId}")
    public String getStatus(@PathVariable String docId) {
        String status = redisTemplate.opsForValue().get("doc:status:" + docId);
        return status != null ? status : "未找到该文档";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {
        return documentService.processDocument(file);
    }

    // 查询所有上传历史
    @GetMapping("/list")
    public List<Document> list() {
        return documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    // 删除文档
    @DeleteMapping("/{id}")
    public String delete(@PathVariable String id) {
        if (!documentRepository.existsById(id)) {
            return "文档不存在";
        }
        documentRepository.deleteById(id);
        // TODO: 这里还需要从ChatService里清理对应切片，后续多文档管理时再细化
        return "删除成功";
    }
}