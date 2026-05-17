package com.xiaozhen.knowledgeagent.controller;

import com.xiaozhen.knowledgeagent.common.ApiResponse;
import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.config.StorageProperties;
import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.model.DocumentMessage;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import com.xiaozhen.knowledgeagent.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final StorageProperties storageProperties;

    @PostMapping("/upload")
    public ApiResponse<String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String result = documentService.processDocument(file);
        if (result.startsWith("上传失败")) {
            return ApiResponse.error(400, result);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/status/{docId}")
    public ApiResponse<String> getStatus(@PathVariable String docId) {
        String status = redisTemplate.opsForValue().get("doc:status:" + docId);
        if (status != null) {
            return ApiResponse.ok(status);
        }
        Document doc = documentRepository.findById(docId).orElse(null);
        if (doc != null) {
            return ApiResponse.ok(doc.getStatus());
        }
        return ApiResponse.error(404, "未找到该文档");
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Document> docs = documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", doc.getId());
            map.put("fileName", doc.getFileName());
            map.put("status", doc.getStatus());
            map.put("chunkCount", doc.getChunkCount());
            map.put("charCount", doc.getCharCount());
            map.put("active", doc.getActive());
            map.put("deleted", doc.getDeleted());
            map.put("createdAt", doc.getCreatedAt());

            String chunksJson = redisTemplate.opsForValue().get("chunks:" + doc.getId());
            boolean chunksExist = chunksJson != null && !chunksJson.isEmpty();
            map.put("chunksExist", chunksExist);
            map.put("needReparse", "SUCCESS".equals(doc.getStatus()) && !chunksExist);
            result.add(map);
        }
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> softDelete(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ApiResponse.error(404, "文档不存在");
        }
        doc.setActive(false);
        doc.setDeleted(true);
        documentRepository.save(doc);
        redisTemplate.opsForValue().increment("doc:version");
        return ApiResponse.ok("已移入回收站");
    }

    @DeleteMapping("/{id}/purge")
    public ApiResponse<String> purge(@PathVariable String id) {
        if (!documentRepository.existsById(id)) {
            return ApiResponse.error(404, "文档不存在");
        }
        documentRepository.deleteById(id);
        redisTemplate.delete("chunks:" + id);
        redisTemplate.delete("doc:status:" + id);
        redisTemplate.opsForValue().increment("doc:version");
        return ApiResponse.ok("已彻底删除");
    }

    @GetMapping("/trash")
    public ApiResponse<List<Document>> trash() {
        return ApiResponse.ok(documentRepository.findByDeletedTrue());
    }

    @PutMapping("/{id}/restore")
    public ApiResponse<String> restore(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ApiResponse.error(404, "文档不存在");
        }
        doc.setActive(true);
        doc.setDeleted(false);
        documentRepository.save(doc);
        redisTemplate.opsForValue().increment("doc:version");
        return ApiResponse.ok("已恢复");
    }

    @PutMapping("/{id}/toggle")
    public ApiResponse<String> toggle(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ApiResponse.error(404, "文档不存在");
        }
        doc.setActive(!doc.getActive());
        documentRepository.save(doc);
        redisTemplate.opsForValue().increment("doc:version");
        return ApiResponse.ok(doc.getActive() ? "已激活" : "已取消激活");
    }

    @PostMapping("/{id}/reparse")
    public ApiResponse<String> reparse(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ApiResponse.error(404, "文档不存在");
        }

        java.io.File dir = new java.io.File(storageProperties.getPath());
        java.io.File[] files = dir.listFiles((d, name) -> name.startsWith(id + "_"));
        if (files == null || files.length == 0) {
            return ApiResponse.error(400, "原始文件不存在，请重新上传");
        }

        try {
            byte[] content = java.nio.file.Files.readAllBytes(files[0].toPath());
            String fileName = doc.getFileName();
            String md5 = doc.getFileMd5();
            String newId = UUID.randomUUID().toString().substring(0, 8);

            Document newDoc = new Document(newId, fileName, "PROCESSING");
            newDoc.setFileMd5(md5);
            newDoc.setActive(true);
            documentRepository.save(newDoc);

            documentRepository.delete(doc);
            redisTemplate.delete("chunks:" + id);
            redisTemplate.delete("embeddings:" + id);
            redisTemplate.delete("doc:status:" + id);
            redisTemplate.opsForValue().increment("doc:version");

            DocumentMessage message = new DocumentMessage(newId, content, fileName);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DOCUMENT, RabbitMQConfig.ROUTING_KEY, message);

            return ApiResponse.ok("重新解析已提交，新文档ID: " + newId);
        } catch (Exception e) {
            throw new RuntimeException("重新解析失败: " + e.getMessage(), e);
        }
    }
}