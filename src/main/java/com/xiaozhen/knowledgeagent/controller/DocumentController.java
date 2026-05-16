package com.xiaozhen.knowledgeagent.controller;

import com.xiaozhen.knowledgeagent.config.StorageProperties;
import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import com.xiaozhen.knowledgeagent.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.model.DocumentMessage;

import java.util.UUID;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public String upload(@RequestParam("file") MultipartFile file) {
        try {
            return documentService.processDocument(file);
        } catch (IOException e) {
            return "文档上传失败: " + e.getMessage();
        }
    }

    @GetMapping("/status/{docId}")
    public String getStatus(@PathVariable String docId) {
        // 优先从Redis查
        String status = redisTemplate.opsForValue().get("doc:status:" + docId);
        if (status != null) {
            return status;
        }
        // Redis没命中，兜底查MySQL
        Document doc = documentRepository.findById(docId).orElse(null);
        return doc != null ? doc.getStatus() : "未找到该文档";
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
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
            // 检查Redis中切片是否存在
            String chunksJson = redisTemplate.opsForValue().get("chunks:" + doc.getId());
            boolean chunksExist = chunksJson != null && !chunksJson.isEmpty();
            map.put("chunksExist", chunksExist);
            // 只有在处理成功但切片丢失时才需要重新解析
            map.put("needReparse", "SUCCESS".equals(doc.getStatus()) && !chunksExist);
            result.add(map);
        }
        return result;
    }
    // 软删除
    @DeleteMapping("/{id}")
    public String softDelete(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return "文档不存在";
        doc.setActive(false);
        doc.setDeleted(true);
        documentRepository.save(doc);
        redisTemplate.opsForValue().increment("doc:version");
        return "已移入回收站";
    }

    // 彻底删除
    @DeleteMapping("/{id}/purge")
    public String purge(@PathVariable String id) {
        if (!documentRepository.existsById(id)) return "文档不存在";
        documentRepository.deleteById(id);
        redisTemplate.delete("chunks:" + id);
        redisTemplate.delete("doc:status:" + id);
        redisTemplate.opsForValue().increment("doc:version");
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
        redisTemplate.opsForValue().increment("doc:version");
        return "已恢复";
    }

    // 切换激活状态
    @PutMapping("/{id}/toggle")
    public String toggle(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return "文档不存在";
        doc.setActive(!doc.getActive());
        documentRepository.save(doc);
        redisTemplate.opsForValue().increment("doc:version");
        return doc.getActive() ? "已激活" : "已取消激活";
    }

    @PostMapping("/{id}/reparse")
    public String reparse(@PathVariable String id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return "文档不存在";

        // 从本地文件读取原始内容
        java.io.File dir = new java.io.File(storageProperties.getPath());
        java.io.File[] files = dir.listFiles((d, name) -> name.startsWith(id + "_"));
        if (files == null || files.length == 0) return "原始文件不存在，请重新上传";

        try {
            byte[] content = java.nio.file.Files.readAllBytes(files[0].toPath());
            String fileName = doc.getFileName();
            String md5 = doc.getFileMd5();

            // 生成新ID
            String newId = UUID.randomUUID().toString().substring(0, 8);

            // 写MySQL新记录
            Document newDoc = new Document(newId, fileName, "PROCESSING");
            newDoc.setFileMd5(md5);
            newDoc.setActive(true);
            documentRepository.save(newDoc);

            // 删旧记录和切片
            documentRepository.delete(doc);
            redisTemplate.delete("chunks:" + id);
            redisTemplate.delete("embeddings:" + id);
            redisTemplate.delete("doc:status:" + id);
            redisTemplate.opsForValue().increment("doc:version");

            // 发消息重新解析
            DocumentMessage message = new DocumentMessage(newId, content, fileName);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DOCUMENT, RabbitMQConfig.ROUTING_KEY, message);

            return "重新解析已提交，新文档ID: " + newId;
        } catch (Exception e) {
            return "重新解析失败: " + e.getMessage();
        }
    }
}