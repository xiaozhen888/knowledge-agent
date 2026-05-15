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
        String originalFileName = file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();

        String md5 = calculateMD5(fileBytes);

        List<Document> existDocs = documentRepository.findByFileName(originalFileName);
        for (Document doc : existDocs) {
            if (md5.equals(doc.getFileMd5())) {
                String chunksJson = redisTemplate.opsForValue().get("chunks:" + doc.getId());
                if (chunksJson != null && !chunksJson.isEmpty()) {
                    doc.setActive(true);
                    doc.setDeleted(false);
                    documentRepository.save(doc);
                    return "文档已恢复，文档ID: " + doc.getId() + "，请稍后提问";
                } else {
                    documentRepository.delete(doc);
                    redisTemplate.delete("chunks:" + doc.getId());
                    redisTemplate.delete("embeddings:" + doc.getId());
                    redisTemplate.delete("doc:status:" + doc.getId());
                    break;
                }
            }
        }

        String uniqueFileName = generateUniqueFileName(originalFileName);
        String docId = UUID.randomUUID().toString().substring(0, 8);

        Document doc = new Document(docId, uniqueFileName, "PROCESSING");
        doc.setFileMd5(md5);
        doc.setActive(true);
        documentRepository.save(doc);

        DocumentMessage message = new DocumentMessage(docId, fileBytes, uniqueFileName);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DOCUMENT, RabbitMQConfig.ROUTING_KEY, message);

        return "文档已提交处理，文档ID: " + docId + "，请稍后提问";
    }

    private String generateUniqueFileName(String originalFileName) {
        int dotIndex = originalFileName.lastIndexOf(".");
        String name = dotIndex > 0 ? originalFileName.substring(0, dotIndex) : originalFileName;
        String ext = dotIndex > 0 ? originalFileName.substring(dotIndex) : "";

        String candidate = originalFileName;
        int count = 1;

        while (documentRepository.existsByFileName(candidate)) {
            candidate = name + "(" + count + ")" + ext;
            count++;
        }

        return candidate;
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