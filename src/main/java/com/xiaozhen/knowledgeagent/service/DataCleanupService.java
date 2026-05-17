package com.xiaozhen.knowledgeagent.service;

import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.repository.ChatHistoryRepository;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import com.xiaozhen.knowledgeagent.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataCleanupService {

    private final ChatHistoryRepository chatHistoryRepository;
    private final DocumentRepository documentRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final StorageProperties storageProperties;

    // 对话历史保留天数
    private static final int HISTORY_RETAIN_DAYS = 30;
    // 回收站文档保留天数
    private static final int TRASH_RETAIN_DAYS = 7;

    /**
     * 每日凌晨 3 点执行全量清理
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyCleanup() {
        log.info("【数据清理】定时任务开始执行...");
        long start = System.currentTimeMillis();

        try {
            cleanupChatHistory();
        } catch (Exception e) {
            log.error("【数据清理】对话历史清理异常", e);
        }

        try {
            cleanupTrashDocuments();
        } catch (Exception e) {
            log.error("【数据清理】回收站文档清理异常", e);
        }

        try {
            cleanupOrphanFiles();
        } catch (Exception e) {
            log.error("【数据清理】孤儿文件扫描异常", e);
        }

        log.info("【数据清理】执行完成，耗时 {}ms", System.currentTimeMillis() - start);
    }

    /**
     * 清理过期对话历史
     */
    private void cleanupChatHistory() {
        LocalDateTime before = LocalDateTime.now().minusDays(HISTORY_RETAIN_DAYS);
        int deleted = chatHistoryRepository.deleteByCreatedAtBefore(before);
        if (deleted > 0) {
            log.info("【数据清理】删除 {} 天前对话历史 {} 条", HISTORY_RETAIN_DAYS, deleted);
        }
    }

    /**
     * 彻底清理回收站中超期的文档（文件 + Redis + MySQL）
     */
    private void cleanupTrashDocuments() {
        LocalDateTime before = LocalDateTime.now().minusDays(TRASH_RETAIN_DAYS);
        List<Document> trashDocs = documentRepository.findByDeletedTrueAndUpdatedAtBefore(before);

        for (Document doc : trashDocs) {
            purgeDocumentCompletely(doc);
        }

        if (!trashDocs.isEmpty()) {
            log.info("【数据清理】回收站彻底清理 {} 个文档", trashDocs.size());
        }
    }

    /**
     * 扫描并清理文件系统中已不存在 MySQL 记录的孤儿文件
     */
    private void cleanupOrphanFiles() {
        File dir = new File(storageProperties.getPath());
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles((d, name) -> name.contains("_"));
        if (files == null) return;

        int deletedCount = 0;
        for (File file : files) {
            // 文件名格式: docId_xxx.pdf
            String name = file.getName();
            int idx = name.indexOf('_');
            if (idx <= 0) continue;

            String docId = name.substring(0, idx);
            // MySQL 里已不存在该文档，则删文件
            if (!documentRepository.existsById(docId)) {
                boolean ok = file.delete();
                if (ok) deletedCount++;
            }
        }

        if (deletedCount > 0) {
            log.info("【数据清理】清理孤儿文件 {} 个", deletedCount);
        }
    }

    /**
     * 彻底删除一个文档的所有痕迹
     */
    private void purgeDocumentCompletely(Document doc) {
        String docId = doc.getId();

        // 1. 删本地文件
        File file = new File(storageProperties.getPath(), docId + "_" + doc.getFileName());
        if (file.exists()) {
            boolean ok = file.delete();
            if (!ok) log.warn("【数据清理】文件删除失败: {}", file.getAbsolutePath());
        }

        // 2. 删 Redis
        redisTemplate.delete("chunks:" + docId);
        redisTemplate.delete("embeddings:" + docId);
        redisTemplate.delete("doc:status:" + docId);

        // 3. 删 MySQL
        documentRepository.delete(doc);
    }
}