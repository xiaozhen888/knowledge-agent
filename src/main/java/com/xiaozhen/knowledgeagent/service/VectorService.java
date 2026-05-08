package com.xiaozhen.knowledgeagent.service;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VectorService {

    /**
     * 混合检索：TF加权关键词匹配
     */
    public List<String> searchRelevant(List<String> chunks, String query, int topK) {
        Set<String> queryWords = tokenize(query);
        Map<String, Double> scores = new HashMap<>();

        for (String chunk : chunks) {
            Set<String> chunkWords = tokenize(chunk);

            // 1. Jaccard相似度（原方案）
            Set<String> intersection = new HashSet<>(queryWords);
            intersection.retainAll(chunkWords);
            Set<String> union = new HashSet<>(queryWords);
            union.addAll(chunkWords);
            double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

            // 2. TF加权：查询词在chunk中出现的频率
            double tfScore = 0;
            for (String qWord : queryWords) {
                int count = countOccurrences(chunk, qWord);
                if (count > 0) {
                    tfScore += Math.log(1 + count); // 对数平滑
                }
            }

            // 3. 综合得分（可调权重）
            double finalScore = jaccard * 0.4 + tfScore * 0.6;
            scores.put(chunk, finalScore);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 向量检索：返回Top-K个最相关的片段
     */
    public List<String> searchByEmbedding(List<String> chunks, List<float[]> embeddings, float[] queryVector, int topK) {
        // 计算每个片段的余弦相似度
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double score = cosineSimilarity(queryVector, embeddings.get(i));
            scored.add(new ScoredChunk(chunks.get(i), score));
        }

        // 按相似度降序排列
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 取Top-K
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(scored.get(i).chunk);
        }
        return result;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // 内部类
    private static class ScoredChunk {
        String chunk;
        double score;
        ScoredChunk(String chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }

    private int countOccurrences(String text, String word) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(word, index)) != -1) {
            count++;
            index += word.length();
        }
        return count;
    }

    private Set<String> tokenize(String text) {
        String[] words = text.split("[，。！？；、\\s,.!?;:：\n]+");
        Set<String> result = new HashSet<>();
        for (String word : words) {
            if (word.trim().length() >= 2) {
                result.add(word.trim());
            }
        }
        return result;
    }
}