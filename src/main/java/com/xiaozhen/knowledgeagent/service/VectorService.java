package com.xiaozhen.knowledgeagent.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VectorService {

    public List<String> searchRelevant(List<String> chunks, String query, int topK) {
        Set<String> queryWords = tokenize(expandSynonyms(query));
        Map<String, Double> scores = new HashMap<>();

        for (String chunk : chunks) {
            Set<String> chunkWords = tokenize(chunk);

            Set<String> intersection = new HashSet<>(queryWords);
            intersection.retainAll(chunkWords);
            Set<String> union = new HashSet<>(queryWords);
            union.addAll(chunkWords);
            double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

            double tfScore = 0;
            for (String qWord : queryWords) {
                int count = countOccurrences(chunk, qWord);
                if (count > 0) {
                    tfScore += Math.log(1 + count);
                }
            }

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
     * 向量检索：全局语义检索，返回带索引和分数的结果（不设TopK限制）
     */
    public List<ScoredChunk> searchByEmbeddingWithScore(List<float[]> embeddings, float[] queryVector, double minScore) {
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            double score = cosineSimilarity(queryVector, embeddings.get(i));
            if (score >= minScore) {
                scored.add(new ScoredChunk(i, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
    }

    /**
     * 向量检索：带TopK限制的旧版本（兼容）
     */
    public List<ScoredChunk> searchByEmbeddingWithScore(List<float[]> embeddings, float[] queryVector, int topK, double minScore) {
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            double score = cosineSimilarity(queryVector, embeddings.get(i));
            if (score >= minScore) {
                scored.add(new ScoredChunk(i, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    public List<String> searchByEmbedding(List<String> chunks, List<float[]> embeddings, float[] queryVector, int topK) {
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            double score = cosineSimilarity(queryVector, embeddings.get(i));
            scored.add(new ScoredChunk(i, score));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(chunks.get(scored.get(i).index));
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

    public static class ScoredChunk {
        public final int index;
        public final double score;

        public ScoredChunk(int index, double score) {
            this.index = index;
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

    private String expandSynonyms(String text) {
        return text
                .replaceAll("批量", "批量 一次性 多个 大量")
                .replaceAll("导入", "导入 上传 载入 导入数据")
                .replaceAll("用户", "用户 账号 成员 客户")
                .replaceAll("删除", "删除 移除 去掉 清空")
                .replaceAll("查询", "查询 查找 搜索 检索")
                .replaceAll("创建", "创建 新建 新增 建立 添加")
                .replaceAll("修改", "修改 编辑 更新 改动 变更")
                .replaceAll("查看", "查看 浏览 显示 展示 查阅")
                .replaceAll("设置", "设置 配置 设定 调整")
                .replaceAll("登录", "登录 登陆 进入")
                .replaceAll("注册", "注册 开户 申请")
                .replaceAll("下载", "下载 导出 保存")
                .replaceAll("上传", "上传 导入 提交")
                .replaceAll("密码", "密码 口令 密钥")
                .replaceAll("权限", "权限 角色 授权 访问控制")
                .replaceAll("订单", "订单 合同 单据")
                .replaceAll("支付", "支付 付款 缴费 结算")
                .replaceAll("通知", "通知 消息 提醒 告警")
                .replaceAll("报表", "报表 报告 统计 图表");
    }

    private Set<String> tokenize(String text) {
        String[] words = text.split("[，。！？；、\\s,.!?;:：\n]+");
        Set<String> result = new HashSet<>();
        for (String word : words) {
            word = word.trim().toLowerCase();
            if (word.length() >= 2) {
                result.add(word);
            }
        }
        return result;
    }
}