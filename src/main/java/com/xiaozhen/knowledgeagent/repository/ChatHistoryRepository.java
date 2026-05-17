// src/main/java/com/xiaozhen/knowledgeagent/repository/ChatHistoryRepository.java
package com.xiaozhen.knowledgeagent.repository;

import com.xiaozhen.knowledgeagent.model.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {
    List<ChatHistory> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Modifying
    @Query("DELETE FROM ChatHistory ch WHERE ch.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);
}