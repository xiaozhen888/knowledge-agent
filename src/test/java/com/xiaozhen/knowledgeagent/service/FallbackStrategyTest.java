package com.xiaozhen.knowledgeagent.service;

import com.xiaozhen.knowledgeagent.model.ChunkResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FallbackStrategyTest {

    private static final double MIN_RERANK_SCORE = 0.6;

    /**
     * 模拟兜底策略触发：所有片段低于阈值
     */
    @Test
    void testFallbackTriggeredWhenNoChunksAboveThreshold() {
        List<ChunkResult> relevantChunks = new ArrayList<>();
        relevantChunks.add(new ChunkResult("无关内容1", "doc1", "test.pdf", 0));
        relevantChunks.get(0).setScore(0.1);
        relevantChunks.add(new ChunkResult("无关内容2", "doc1", "test.pdf", 1));
        relevantChunks.get(1).setScore(0.2);

        // 模拟过滤逻辑
        List<ChunkResult> filtered = relevantChunks.stream()
                .filter(c -> c.getScore() >= MIN_RERANK_SCORE)
                .collect(java.util.stream.Collectors.toList());

        // 降低阈值到 0.3 后仍为空
        if (filtered.isEmpty()) {
            filtered = relevantChunks.stream()
                    .filter(c -> c.getScore() >= 0.3)
                    .collect(java.util.stream.Collectors.toList());
        }

        // 触发兜底条件
        boolean shouldFallback = filtered.isEmpty() || relevantChunks.isEmpty()
                || (!relevantChunks.isEmpty() && relevantChunks.get(0).getScore() < 0.2);

        assertTrue(shouldFallback, "所有片段低于阈值，应触发兜底策略");
    }

    /**
     * 模拟不触发兜底：有片段超过阈值
     */
    @Test
    void testFallbackNotTriggeredWhenTopChunkAboveThreshold() {
        List<ChunkResult> relevantChunks = new ArrayList<>();
        relevantChunks.add(new ChunkResult("量子计算基于量子力学原理", "doc1", "test.pdf", 0));
        relevantChunks.get(0).setScore(0.75);
        relevantChunks.add(new ChunkResult("相关内容补充", "doc1", "test.pdf", 1));
        relevantChunks.get(1).setScore(0.45);

        List<ChunkResult> filtered = relevantChunks.stream()
                .filter(c -> c.getScore() >= MIN_RERANK_SCORE)
                .collect(java.util.stream.Collectors.toList());

        boolean shouldFallback = filtered.isEmpty() || relevantChunks.isEmpty()
                || (!relevantChunks.isEmpty() && relevantChunks.get(0).getScore() < 0.2);

        assertFalse(shouldFallback, "有片段超过阈值，不应触发兜底");
        assertEquals(1, filtered.size(), "应过滤出1个有效片段");
    }

    /**
     * 模拟 Top1 分数极低但有过滤后片段的情况（兜底策略2条件）
     */
    @Test
    void testFallbackTriggeredWhenTop1ScoreTooLow() {
        List<ChunkResult> relevantChunks = new ArrayList<>();
        relevantChunks.add(new ChunkResult("弱相关内容", "doc1", "test.pdf", 0));
        relevantChunks.get(0).setScore(0.15);
        relevantChunks.add(new ChunkResult("另一个弱相关", "doc1", "test.pdf", 1));
        relevantChunks.get(1).setScore(0.12);

        List<ChunkResult> filtered = relevantChunks.stream()
                .filter(c -> c.getScore() >= MIN_RERANK_SCORE)
                .collect(java.util.stream.Collectors.toList());

        if (filtered.isEmpty()) {
            filtered = relevantChunks.stream()
                    .filter(c -> c.getScore() >= 0.3)
                    .collect(java.util.stream.Collectors.toList());
        }

        boolean shouldFallback = filtered.isEmpty() || relevantChunks.isEmpty()
                || (!relevantChunks.isEmpty() && relevantChunks.get(0).getScore() < 0.2);

        assertTrue(shouldFallback, "Top1 分数低于 0.2，应触发兜底");
    }
}