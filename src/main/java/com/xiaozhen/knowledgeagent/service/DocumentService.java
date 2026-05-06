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
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final RabbitTemplate rabbitTemplate;
    private final DocumentRepository documentRepository;

    public String processDocument(MultipartFile file) throws IOException {
        String docId = UUID.randomUUID().toString().substring(0, 8);
        byte[] fileBytes = file.getBytes();

        // 1. 先写MySQL，状态PROCESSING
        Document doc = new Document(docId, file.getOriginalFilename(), "PROCESSING");
        documentRepository.save(doc);

        // 2. 发消息
        DocumentMessage message = new DocumentMessage(docId, fileBytes, file.getOriginalFilename());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DOCUMENT, RabbitMQConfig.ROUTING_KEY, message);

        return "文档已提交处理，文档ID: " + docId + "，请稍后提问";
    }
}