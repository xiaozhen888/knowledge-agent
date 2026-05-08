package com.xiaozhen.knowledgeagent.service;

import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.model.DocumentMessage;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final RabbitTemplate rabbitTemplate;
    private final DocumentRepository documentRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public String processDocument(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();

        // 计算文件MD5
        String md5 = calculateMD5(fileBytes);

        // 查重：同名且MD5相同的文件
        List<Document> existDocs = documentRepository.findByFileName(fileName);
        for (Document doc : existDocs) {
            if (md5.equals(doc.getFileMd5())) {
                // 检查Redis中切片是否还存在
                String chunksJson = redisTemplate.opsForValue().get("chunks:" + doc.getId());
                if (chunksJson != null && !chunksJson.isEmpty()) {
                    // 切片还在，直接激活
                    doc.setActive(true);
                    doc.setDeleted(false);
                    documentRepository.save(doc);
                    return "该文档已在回收站中，已自动恢复并激活（ID: " + doc.getId() + "）";
                } else {
                    // 切片过期了，删旧记录，走正常解析流程
                    documentRepository.delete(doc);
                    redisTemplate.delete("chunks:" + doc.getId());
                    redisTemplate.delete("embeddings:" + doc.getId());
                    redisTemplate.delete("doc:status:" + doc.getId());
                    break;
                }
            }
        }

        // 不存在或内容不同，正常处理
        String docId = UUID.randomUUID().toString().substring(0, 8);

        Document doc = new Document(docId, fileName, "PROCESSING");
        doc.setFileMd5(md5);
        doc.setActive(true);
        documentRepository.save(doc);

        DocumentMessage message = new DocumentMessage(docId, fileBytes, fileName);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DOCUMENT, RabbitMQConfig.ROUTING_KEY, message);

        return "文档已提交处理，文档ID: " + docId + "，请稍后提问";
    }

    private String calculateMD5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }
}