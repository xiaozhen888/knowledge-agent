package com.xiaozhen.knowledgeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhen.knowledgeagent.model.CachedAnswer;
import com.xiaozhen.knowledgeagent.model.ChatHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class HotQuestionCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 热点门槛：24小时内被问 >= 3 次自动晋升
    private static final int HOT_THRESHOLD = 3;
    private static final String KEY_ANSWERS = "rag:hotqa:answers";
    private static final String KEY_COUNTER = "rag:hotqa:counter";
    private static final String KEY_STATS_HIT = "rag:hotqa:stats:hit";
    private static final String KEY_STATS_MISS = "rag:hotqa:stats:miss";

    /**
     * 查询热点缓存
     */
    public CachedAnswer get(String question) {
        try {
            Object jsonObj = redisTemplate.opsForHash().get(KEY_ANSWERS, question.trim());
            if (jsonObj == null) {
                redisTemplate.opsForHash().increment(KEY_STATS_MISS, "total", 1);
                return null;
            }
            String json = jsonObj.toString();
            redisTemplate.opsForHash().increment(KEY_STATS_HIT, "total", 1);
            log.info("🎯 热点缓存命中: {}", question);
            return objectMapper.readValue(json, CachedAnswer.class);
        } catch (Exception e) {
            log.error("热点缓存读取异常", e);
            return null;
        }
    }

    /**
     * 记录问题被问次数，并判断是否自动晋升热点
     */
    public void recordQuestion(String question, String answer, List<String> sources) {
        try {
            String q = question.trim();

            // 1. 原子计数
            Double count = redisTemplate.opsForZSet().incrementScore(KEY_COUNTER, q, 1);

            // 2. 设置过期时间（只在 key 不存在时设，避免每次重置 24h）
            Boolean exists = redisTemplate.hasKey(KEY_COUNTER);
            if (Boolean.FALSE.equals(exists)) {
                redisTemplate.expire(KEY_COUNTER, 24, TimeUnit.HOURS);
            }

            // 3. 达到阈值时，原子晋升（HSETNX）
            if (count != null && count >= HOT_THRESHOLD) {
                CachedAnswer ca = new CachedAnswer();
                ca.setAnswer(answer);
                ca.setSources(sources);
                ca.setCachedAt(System.currentTimeMillis());

                Boolean success = redisTemplate.opsForHash()
                        .putIfAbsent(KEY_ANSWERS, q, objectMapper.writeValueAsString(ca));

                if (Boolean.TRUE.equals(success)) {
                    log.info("问题自动晋升热点缓存: {} ({}次)", q, count.intValue());
                }
            }
        } catch (Exception e) {
            log.error("热点计数异常", e);
        }
    }

    /**
     * 手动添加热点问题（管理后台用）
     */
    public void addHotQuestion(String question, String answer, List<String> sources) {
        try {
            CachedAnswer ca = new CachedAnswer();
            ca.setAnswer(answer);
            ca.setSources(sources);
            ca.setCachedAt(System.currentTimeMillis());

            redisTemplate.opsForHash().put(KEY_ANSWERS, question.trim(),
                    objectMapper.writeValueAsString(ca));
            log.info("手动添加热点问题: {}", question);
        } catch (Exception e) {
            throw new RuntimeException("添加热点问题失败", e);
        }
    }

    /**
     * 删除热点问题
     */
    public void removeHotQuestion(String question) {
        redisTemplate.opsForHash().delete(KEY_ANSWERS, question.trim());
        redisTemplate.opsForZSet().remove(KEY_COUNTER, question.trim());
    }

    /**
     * 获取缓存命中率
     */
    public String getStats() {
        String hit = (String) redisTemplate.opsForHash().get(KEY_STATS_HIT, "total");
        String miss = (String) redisTemplate.opsForHash().get(KEY_STATS_MISS, "total");
        long h = hit == null ? 0 : Long.parseLong(hit);
        long m = miss == null ? 0 : Long.parseLong(miss);
        long total = h + m;
        double rate = total == 0 ? 0 : (double) h / total * 100;
        return String.format("热点缓存命中: %d, 未命中: %d, 命中率: %.2f%%", h, m, rate);
    }

    /**
     * 列出当前热点问题
     */
    public List<String> listHotQuestions() {
        return redisTemplate.opsForHash().keys(KEY_ANSWERS).stream()
                .map(Object::toString)
                .toList();
    }
}