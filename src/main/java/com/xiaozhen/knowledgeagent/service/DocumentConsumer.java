package com.xiaozhen.knowledgeagent.service;

import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.model.DocumentMessage;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentConsumer {

    private final ChatService chatService;
    private final RedisTemplate<String, String> redisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_DOCUMENT)
    public void handleDocument(DocumentMessage message) {
        String docId = message.getDocId();
        try {
            updateStatus(docId, "PROCESSING");

            // 用 PDDocument.load() 而不是 Loader.load()
            PDDocument document = PDDocument.load(new ByteArrayInputStream(message.getContent()));
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            document.close();

            List<String> chunks = splitText(text, 500, 100);
            chatService.storeChunks(chunks);

            updateStatus(docId, "SUCCESS");
            System.out.println("文档 " + docId + " 处理完成，共 " + chunks.size() + " 个片段");

        } catch (Exception e) {
            e.printStackTrace();
            updateStatus(docId, "FAILED: " + e.getMessage());
        }
    }

    private void updateStatus(String docId, String status) {
        redisTemplate.opsForValue().set("doc:status:" + docId, status, Duration.ofHours(1));
    }

    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start += (chunkSize - overlap);
        }
        return chunks;
    }
}