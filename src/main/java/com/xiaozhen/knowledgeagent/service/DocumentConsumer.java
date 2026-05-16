package com.xiaozhen.knowledgeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhen.knowledgeagent.config.RabbitMQConfig;
import com.xiaozhen.knowledgeagent.config.StorageProperties;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
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
    private final EmbeddingService embeddingService;
    private final StorageProperties storageProperties;

    @Transactional
    @RabbitListener(queues = RabbitMQConfig.QUEUE_DOCUMENT)
    public void handleDocument(DocumentMessage message) {
        String docId = message.getDocId();
        try {
            updateStatus(docId, "PROCESSING");

            String rawText = extractText(message.getContent(), message.getFileName());
            String text = cleanPdfText(rawText);  // 清洗
            String correctedText = reorderPdfText(text);

            List<String> chunks = splitText(correctedText, 500, 100);

            saveOriginalFile(docId, message.getContent(), message.getFileName());

            List<float[]> embeddings = new ArrayList<>();
            for (String chunk : chunks) {
                float[] vec = embeddingService.embed(chunk);
                embeddings.add(vec);
            }
            redisTemplate.opsForValue().set(
                    "embeddings:" + docId,
                    objectMapper.writeValueAsString(embeddings),
                    24, TimeUnit.HOURS
            );

            redisTemplate.opsForValue().set("chunks:" + docId, objectMapper.writeValueAsString(chunks), 24, TimeUnit.HOURS);

            Document doc = documentRepository.findById(docId).orElse(null);
            if (doc != null) {
                doc.setStatus("SUCCESS");
                doc.setChunkCount(chunks.size());
                doc.setCharCount(text.length());
                documentRepository.save(doc);
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    updateStatus(docId, "SUCCESS");
                    // 文档数据就绪，递增版本号使旧精排缓存失效
                    redisTemplate.opsForValue().increment("doc:version");
                    System.out.println("文档 " + docId + " 处理完成，共 " + chunks.size() + " 个片段，精排缓存版本号已递增");
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    updateStatus(docId, "FAILED: " + e.getMessage());
                }
            });
        }
    }

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
        PDDocument pdfDocument = PDDocument.load(content);
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setShouldSeparateByBeads(true);
        String text = stripper.getText(pdfDocument);
        pdfDocument.close();
        return text;
    }

    private String cleanPdfText(String rawText) {
        String[] lines = rawText.split("\n");
        List<String> cleaned = new ArrayList<>();
        boolean inContent = false; // 跳过目录页

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 去掉行首的页码数字（如 "51 但是如果..." → "但是如果..."）
            // 匹配：行首是1-3位数字，后面跟着空格或中文
            trimmed = trimmed.replaceAll("^[\\s]*\\d{1,3}[\\s]+", "");

            // 跳过目录页特征：包含大量"重点/扩展"字样的短行
            if (trimmed.contains("重点") && trimmed.contains("扩展") && trimmed.length() < 100) {
                continue;
            }

            // 跳过纯页码行（只有数字）
            if (trimmed.matches("^\\d+$")) {
                continue;
            }

            cleaned.add(trimmed);
        }

        return String.join("\n", cleaned);
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

    private List<String> splitText(String text, int maxLen, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\\s*\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim().replaceAll("\\s+", " ");
            if (para.isEmpty()) continue;

            if (para.length() > maxLen) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitLongParagraph(para, maxLen, overlap));
            } else {
                if (current.length() + para.length() + 2 > maxLen && current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(para);
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    private List<String> splitLongParagraph(String text, int maxLen, int overlap) {
        List<String> result = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？；.])\\s*");
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;

            if (sentence.length() > maxLen) {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current = new StringBuilder();
                }
                for (int i = 0; i < sentence.length(); i += maxLen - overlap) {
                    int end = Math.min(i + maxLen, sentence.length());
                    result.add(sentence.substring(i, end));
                }
                continue;
            }

            if (current.length() + sentence.length() > maxLen && current.length() > 0) {
                result.add(current.toString().trim());
                String keep = current.substring(Math.max(0, current.length() - overlap));
                current = new StringBuilder(keep);
            }
            current.append(sentence);
        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private boolean isPunctuation(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '，' || c == '、' || c == '\n' || c == ' ';
    }

    private void saveOriginalFile(String docId, byte[] content, String fileName) {
        try {
            File dir = new File(storageProperties.getPath());
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                System.out.println("创建目录 " + storageProperties.getPath() + ": " + created);
            }
            File targetFile = new File(dir, docId + "_" + fileName);
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                fos.write(content);
                fos.flush();
            }
            System.out.println("原始文件已保存: " + targetFile.getAbsolutePath() + "，大小: " + content.length);
        } catch (Exception e) {
            System.err.println("保存原始文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String reorderPdfText(String rawText) {
        String[] lines = rawText.split("\n");
        List<String> fixedLines = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            boolean looksLikeTitle = trimmed.length() <= 50
                    && !trimmed.endsWith("。")
                    && !trimmed.endsWith("，")
                    && !trimmed.startsWith("●")
                    && !trimmed.startsWith("-");

            if (looksLikeTitle) {
                fixedLines.add("\n\n" + trimmed);
            } else {
                fixedLines.add(trimmed);
            }
        }

        return String.join("\n", fixedLines).replaceAll("\n{3,}", "\n\n");
    }

    private String formatPdfText(String rawText) {
        String[] paragraphs = rawText.split("\n\\s*\n");
        List<String> cleanParagraphs = new ArrayList<>();

        for (String para : paragraphs) {
            String trimmedPara = para.trim();
            if (trimmedPara.isEmpty()) continue;
            String cleanLine = trimmedPara.replaceAll("\r?\n", " ");
            cleanLine = cleanLine.replaceAll("\\s{2,}", " ");
            cleanParagraphs.add(cleanLine);
        }

        return String.join("\n\n", cleanParagraphs);
    }
}