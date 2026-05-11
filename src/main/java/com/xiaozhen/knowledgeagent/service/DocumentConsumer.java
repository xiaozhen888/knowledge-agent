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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final EmbeddingService embeddingService;

    @Transactional
    @RabbitListener(queues = RabbitMQConfig.QUEUE_DOCUMENT)
    public void handleDocument(DocumentMessage message) {
        String docId = message.getDocId();
        try {
            // 先写Redis
            updateStatus(docId, "PROCESSING");

            // 解析文档
            String rawText = extractText(message.getContent(), message.getFileName());
            String text = formatPdfText(rawText);
            String correctedText = reorderPdfText(text);
            List<String> chunks = splitText(correctedText, 500, 0);

            // 解析成功后，保存原始文件到本地
            saveOriginalFile(docId, message.getContent(), message.getFileName());

            // 计算每个切片的向量，存 Redis
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

            // 切片存Redis
            redisTemplate.opsForValue().set("chunks:" + docId, objectMapper.writeValueAsString(chunks), 24, TimeUnit.HOURS);

            // 写MySQL（事务内）
            Document doc = documentRepository.findById(docId).orElse(null);
            if (doc != null) {
                doc.setStatus("SUCCESS");
                doc.setChunkCount(chunks.size());
                doc.setCharCount(text.length());
                documentRepository.save(doc);
            }

            // 事务提交后再写Redis状态，保证一致性
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    updateStatus(docId, "SUCCESS");
                    System.out.println("文档 " + docId + " 处理完成，共 " + chunks.size() + " 个片段");
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
        PDDocument pdfDocument = PDDocument.load(content);

        // 关键：设置按页面阅读顺序提取文本，而不是按写入顺序
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);   // 按页面坐标排序（上->下，左->右）
        stripper.setShouldSeparateByBeads(true); // 按文本块分离

        String text = stripper.getText(pdfDocument);
        pdfDocument.close();
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

    private List<String> splitText(String text, int maxLen, int overlap) {
        List<String> chunks = new ArrayList<>();

        // 1. 先按空行分割成自然段落
        String[] paragraphs = text.split("\\n\\s*\\n");

        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim().replaceAll("\\s+", " ");  // 去多余空格
            if (para.isEmpty()) continue;

            // 如果当前段落本身就很长，需要进一步切分
            if (para.length() > maxLen) {
                // 先把之前累积的内容保存
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                // 长段落按句子切分
                chunks.addAll(splitLongParagraph(para, maxLen, overlap));
            } else {
                // 短段落，尝试合并到当前片段
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

        // 最后一段
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    private List<String> splitLongParagraph(String text, int maxLen, int overlap) {
        List<String> result = new ArrayList<>();

        // 先按句子拆分
        String[] sentences = text.split("(?<=[。！？；.])\\s*");
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;

            // 如果单句就超过maxLen，强制切分（很少见）
            if (sentence.length() > maxLen) {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current = new StringBuilder();
                }
                // 按字数硬切
                for (int i = 0; i < sentence.length(); i += maxLen) {
                    int end = Math.min(i + maxLen, sentence.length());
                    result.add(sentence.substring(i, end));
                }
                continue;
            }

            // 正常情况：按句子累加
            if (current.length() + sentence.length() > maxLen && current.length() > 0) {
                result.add(current.toString().trim());
                current = new StringBuilder();
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
            java.io.File dir = new java.io.File("/data/files");
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                System.out.println("创建目录 /data/files: " + created);
            }
            java.io.File targetFile = new java.io.File(dir, docId + "_" + fileName);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                fos.write(content);
                fos.flush();
            }
            System.out.println("原始文件已保存: " + targetFile.getAbsolutePath() + "，大小: " + content.length);
        } catch (Exception e) {
            System.err.println("保存原始文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 修复 PDF 提取文本时因分栏布局、页眉页脚等导致的段落错乱问题
     */
    private String reorderPdfText(String rawText) {
        // 先拆分成行
        String[] lines = rawText.split("\\n");
        List<String> fixedLines = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 如果这一行是一个独立的标题（短句、没有句号、可能以数字开头）
            boolean looksLikeTitle = trimmed.length() <= 50
                    && !trimmed.endsWith("。")
                    && !trimmed.endsWith("，")
                    && !trimmed.startsWith("●")
                    && !trimmed.startsWith("-");

            if (looksLikeTitle) {
                // 强制这个标题自成一段：在前一行后补两个换行，然后追加标题行
                fixedLines.add("\n\n" + trimmed);
            } else {
                // 普通正文行直接追加
                fixedLines.add(trimmed);
            }
        }

        // 重新拼合成完整文本，去掉开头可能的多余换行
        return String.join("\n", fixedLines).replaceAll("\\n{3,}", "\n\n");
    }

    /**
     * 修复 PDF 提取文本中常见的换行错误
     */
    private String formatPdfText(String rawText) {
        // 1. 先按空行分割成段落，保护真正的段落边界
        String[] paragraphs = rawText.split("\\n\\s*\\n");
        List<String> cleanParagraphs = new ArrayList<>();

        for (String para : paragraphs) {
            String trimmedPara = para.trim();
            if (trimmedPara.isEmpty()) continue;

            // 2. 把段落内部 PDF 造成的物理换行，替换为空格（中文语境）
            // 英文/数字结尾的行，可能是正常的换行，用空格连接
            String cleanLine = trimmedPara.replaceAll("\\r?\\n", " ");
            // 去掉多余的空格
            cleanLine = cleanLine.replaceAll("\\s{2,}", " ");
            cleanParagraphs.add(cleanLine);
        }

        return String.join("\n\n", cleanParagraphs);
    }
}