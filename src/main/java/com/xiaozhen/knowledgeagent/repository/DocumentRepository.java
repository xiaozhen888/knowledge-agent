package com.xiaozhen.knowledgeagent.repository;

import com.xiaozhen.knowledgeagent.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByFileName(String fileName);
    List<Document> findByActiveTrueAndDeletedFalse();
    List<Document> findByDeletedTrue();
    boolean existsByFileName(String fileName);
}