package com.xiaozhen.knowledgeagent.service;

import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.model.DocumentMessage;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

    public String processDocument(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();

        // 计算文件MD5
        String md5 = calculateMD5(fileBytes);

        // 查重：同名且MD5相同的文件
        List<Document> existDocs = documentRepository.findByFileName(fileName);
        for (Document doc : existDocs) {
            if (md5.equals(doc.getFileMd5())) {
                // 文件已存在，直接激活并返回
                doc.setActive(true);
                doc.setDeleted(false);  // 从回收站恢复
                documentRepository.save(doc);
                return "该文档已存在（ID: " + doc.getId() + "），已重新激活，无需重复解析";
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