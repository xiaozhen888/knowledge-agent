package com.xiaozhen.knowledgeagent.service;

import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.model.DocumentMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.Serializable;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final RabbitTemplate rabbitTemplate;

    public String processDocument(MultipartFile file) throws IOException {
        // 生成文档ID
        String docId = UUID.randomUUID().toString().substring(0, 8);

        // 读取文件内容，和docId一起打包发送到消息队列
        byte[] fileBytes = file.getBytes();
        DocumentMessage message = new DocumentMessage(docId, fileBytes, file.getOriginalFilename());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_DOCUMENT,
                RabbitMQConfig.ROUTING_KEY,
                message
        );

        return "文档已提交处理，文档ID: " + docId + "，请稍后提问";
    }

}