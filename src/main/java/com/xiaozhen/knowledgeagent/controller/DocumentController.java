package com.xiaozhen.knowledgeagent.controller;

import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
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
    private final DocumentRepository documentRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        try {
            return documentService.processDocument(file);
        } catch (IOException e) {
            return "文档上传失败: " + e.getMessage();
        }
    }

    @GetMapping("/status/{docId}")
    public String getStatus(@PathVariable String docId) {
        String status = redisTemplate.opsForValue().get("doc:status:" + docId);
        return status != null ? status : "未找到该文档";
    }

    // 正常文档列表（不含已删除）
    @GetMapping("/list")
    public List<Document> list() {
        return documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    // 软删除
    @DeleteMapping("/{id}")
    public String softDelete(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return "文档不存在";
        doc.setActive(false);
        doc.setDeleted(true);
        documentRepository.save(doc);
        return "已移入回收站";
    }

    // 彻底删除
    @DeleteMapping("/{id}/purge")
    public String purge(@PathVariable String id) {
        if (!documentRepository.existsById(id)) return "文档不存在";
        documentRepository.deleteById(id);
        redisTemplate.delete("chunks:" + id);
        redisTemplate.delete("doc:status:" + id);
        return "已彻底删除";
    }

    // 回收站列表
    @GetMapping("/trash")
    public List<Document> trash() {
        return documentRepository.findByDeletedTrue();
    }

    // 恢复文档
    @PutMapping("/{id}/restore")
    public String restore(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return "文档不存在";
        doc.setActive(true);
        doc.setDeleted(false);
        documentRepository.save(doc);
        return "已恢复";
    }

    // 切换激活状态
    @PutMapping("/{id}/toggle")
    public String toggle(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return "文档不存在";
        doc.setActive(!doc.getActive());
        documentRepository.save(doc);
        return doc.getActive() ? "已激活" : "已取消激活";
    }
}