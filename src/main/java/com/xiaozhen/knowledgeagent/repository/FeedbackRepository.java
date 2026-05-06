package com.xiaozhen.knowledgeagent.repository;

import com.xiaozhen.knowledgeagent.model.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    long countByRating(String rating);
}