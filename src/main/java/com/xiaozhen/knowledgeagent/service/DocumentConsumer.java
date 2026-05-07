package com.xiaozhen.knowledgeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.model.DocumentMessage;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class DocumentConsumer {

    private final RedisTemplate<String, String> redisTemplate;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = RabbitMQConfig.QUEUE_DOCUMENT)
    public void handleDocument(DocumentMessage message) {
        String docId = message.getDocId();
        try {
            updateStatus(docId, "PROCESSING");

            String text = extractText(message.getContent(), message.getFileName());

            List<String> chunks = splitText(text, 500, 100);
            String chunksKey = "chunks:" + docId;
            redisTemplate.opsForValue().set(chunksKey, objectMapper.writeValueAsString(chunks), 24, TimeUnit.HOURS);

            Document doc = documentRepository.findById(docId).orElse(null);
            if (doc != null) {
                doc.setStatus("SUCCESS");
                doc.setChunkCount(chunks.size());
                doc.setCharCount(text.length());
                documentRepository.save(doc);
            }

            updateStatus(docId, "SUCCESS");
            System.out.println("文档 " + docId + " 处理完成，共 " + chunks.size() + " 个片段");

        } catch (Exception e) {
            e.printStackTrace();
            updateStatus(docId, "FAILED: " + e.getMessage());
        }
    }

    /**
     * 根据文件名后缀选择解析器
     */
    private String extractText(byte[] content, String fileName) throws Exception {
        String lowerName = fileName.toLowerCase();

        if (lowerName.endsWith(".pdf")) {
            return extractPdfText(content);
        } else if (lowerName.endsWith(".docx")) {
            return extractDocxText(content);
        } else if (lowerName.endsWith(".md") || lowerName.endsWith(".txt")) {
            return new String(content);
        } else {
            throw new IllegalArgumentException("不支持的文件格式: " + fileName);
        }
    }

    private String extractPdfText(byte[] content) throws Exception {
        PDDocument document = PDDocument.load(new ByteArrayInputStream(content));
        PDFTextStripper stripper = new PDFTextStripper();
        String text = stripper.getText(document);
        document.close();
        return text;
    }

    private String extractDocxText(byte[] content) throws Exception {
        XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
        StringBuilder sb = new StringBuilder();
        document.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
        document.close();
        return sb.toString();
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