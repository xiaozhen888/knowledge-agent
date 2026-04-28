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