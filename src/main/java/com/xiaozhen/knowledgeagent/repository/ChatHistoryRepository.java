// src/main/java/com/xiaozhen/knowledgeagent/repository/ChatHistoryRepository.java
package com.xiaozhen.knowledgeagent.repository;

import com.xiaozhen.knowledgeagent.model.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {
    List<ChatHistory> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}