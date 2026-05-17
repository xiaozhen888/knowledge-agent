package com.xiaozhen.knowledgeagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RerankCacheTest {

    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
    }

    /**
     * 测试规范化查询生成稳定缓存 key
     */
    @Test
    void testNormalizeQueryGeneratesStableKey() {
        String q1 = "  量子计算的原理是什么？  ";
        String q2 = "量子计算的原理是什么";
        String q3 = "量子计算的原理是什么！";

        String n1 = normalizeQuery(q1);
        String n2 = normalizeQuery(q2);
        String n3 = normalizeQuery(q3);

        assertEquals(n1.hashCode(), n2.hashCode(), "空格和问号不影响 key");
        assertEquals(n1.hashCode(), n3.hashCode(), "感叹号和问号不影响 key");
    }

    /**
     * 测试缓存 key 包含文档版本号
     */
    @Test
    void testCacheKeyIncludesDocVersion() {
        String version = "3";
        String normalized = normalizeQuery("测试问题");
        String key = buildRerankKey(version, normalized);

        assertTrue(key.startsWith("rerank:v3:"), "key 应包含版本号前缀");
        assertTrue(key.endsWith(String.valueOf(normalized.hashCode())), "key 应以查询 hash 结尾");
    }

    /**
     * 测试文档版本变更后旧缓存失效
     */
    @Test
    void testOldCacheInvalidatedAfterVersionBump() {
        String oldVersion = "2";
        String newVersion = "3";
        String question = "测试问题";

        String oldKey = buildRerankKey(oldVersion, normalizeQuery(question));
        String newKey = buildRerankKey(newVersion, normalizeQuery(question));

        assertNotEquals(oldKey, newKey, "版本变更后 key 应不同");
    }

    /**
     * 测试不同问题生成不同 key
     */
    @Test
    void testDifferentQuestionsGenerateDifferentKeys() {
        String version = "1";
        String q1 = "量子计算";
        String q2 = "神经网络";

        String key1 = buildRerankKey(version, normalizeQuery(q1));
        String key2 = buildRerankKey(version, normalizeQuery(q2));

        assertNotEquals(key1, key2, "不同问题应生成不同 key");
    }

    /**
     * 测试相同问题相同版本生成相同 key
     */
    @Test
    void testSameQuestionSameVersionSameKey() {
        String version = "1";
        String q = "深度学习";

        String key1 = buildRerankKey(version, normalizeQuery(q));
        String key2 = buildRerankKey(version, normalizeQuery(q));

        assertEquals(key1, key2, "相同问题相同版本应生成相同 key");
    }

    // ========== 辅助方法（复制自 ChatService）==========

    private String normalizeQuery(String q) {
        return q.trim()
                .toLowerCase()
                .replaceAll("[\\p{Punct}\\s]+", "")
                .replaceAll("[的了吗呢啊呀]", "");
    }

    private String buildRerankKey(String version, String normalizedQuery) {
        return "rerank:v" + version + ":" + normalizedQuery.hashCode();
    }
}