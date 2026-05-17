package com.xiaozhen.knowledgeagent.repository;

import com.xiaozhen.knowledgeagent.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByFileName(String fileName);
    List<Document> findByActiveTrueAndDeletedFalse();
    List<Document> findByDeletedTrue();
    boolean existsByFileName(String fileName);

    @Query("SELECT d FROM Document d WHERE d.deleted = true AND d.createdAt < :before")
    List<Document> findByDeletedTrueAndUpdatedAtBefore(@Param("before") LocalDateTime before);
}