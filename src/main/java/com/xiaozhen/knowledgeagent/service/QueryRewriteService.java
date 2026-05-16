package com.xiaozhen.knowledgeagent.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    @Value("${langchain4j.openai.api-key}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.openai.model-name}")
    private String modelName;

    private final RedisTemplate<String, String> redisTemplate;

    private ChatLanguageModel model;

    // 指代词列表：包含这些词才需要重写
    private static final String[] PRONOUNS = {
            "它", "他", "她", "这个", "那个", "上述", "前面", "之前",
            "后者", "前者", "其", "它们", "他们", "她们", "此", "该"
    };

    // 缓存过期时间（分钟）
    private static final int REWRITE_CACHE_MINUTES = 5;

    @PostConstruct
    public void init() {
        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 根据历史对话重写查询，补全省略的主语和指代
     *
     * 优化策略：
     * 1. 无历史对话 → 直接返回原问题
     * 2. 不含指代词 → 直接返回原问题（节省90% LLM调用）
     * 3. 有缓存 → 直接返回缓存结果
     * 4. 否则调用LLM重写
     */
    public String rewrite(String currentQuestion, List<Map<String, String>> history) {
        // 1. 没有历史，不需要重写
        if (history == null || history.isEmpty()) {
            return currentQuestion;
        }

        // 2. 不含指代词，不需要重写（性能优化核心）
        if (!needsRewrite(currentQuestion)) {
            logger.debug("【查询重写】不含指代词，跳过重写: '{}'", currentQuestion);
            return currentQuestion;
        }

        // 3. 检查缓存（避免重复调用LLM）
        String cacheKey = "rewrite:" + currentQuestion.hashCode();
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                logger.debug("【查询重写】缓存命中: '{}' → '{}'", currentQuestion, cached);
                return cached;
            }
        } catch (Exception e) {
            logger.warn("【查询重写】缓存读取失败", e);
        }

        // 4. 构建历史对话上下文（最近3轮）
        StringBuilder historyContext = new StringBuilder();
        int start = Math.max(0, history.size() - 3);
        for (int i = start; i < history.size(); i++) {
            Map<String, String> turn = history.get(i);
            historyContext.append("用户：").append(turn.get("user")).append("\n");
            historyContext.append("助手：").append(turn.get("assistant")).append("\n");
        }

        String prompt = """
                你是一位查询重写专家。请根据历史对话，将用户的当前问题改写为一个完整的、独立的问句。

                要求：
                1. 补全省略的主语、宾语和指代词（如"它""这个""那个"）
                2. 保留用户问题的原始意图
                3. 如果当前问题已经是完整的独立问句，直接返回原句
                4. 不要添加问题以外的任何解释
                5. 只输出改写后的问句，不要加任何前缀或说明

                【历史对话】
                %s

                【当前问题】
                %s

                【改写后的独立问句】
                """.formatted(historyContext.toString(), currentQuestion);

        try {
            String rewritten = model.generate(prompt).trim();
            logger.info("【查询重写】'{}' → '{}'", currentQuestion, rewritten);

            // 5. 结果校验
            if (rewritten.isEmpty()
                    || rewritten.contains("改写后的独立问句")
                    || rewritten.length() > currentQuestion.length() * 3) {
                logger.warn("【查询重写】结果异常，返回原问题: '{}'", currentQuestion);
                return currentQuestion;
            }

            // 6. 缓存结果
            try {
                redisTemplate.opsForValue().set(cacheKey, rewritten, REWRITE_CACHE_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                logger.warn("【查询重写】缓存写入失败", e);
            }

            return rewritten;

        } catch (Exception e) {
            logger.warn("【查询重写】LLM调用失败，返回原问题: {}", currentQuestion, e);
            return currentQuestion;
        }
    }

    /**
     * 判断问题是否需要重写：检查是否包含指代词
     */
    private boolean needsRewrite(String question) {
        for (String pronoun : PRONOUNS) {
            if (question.contains(pronoun)) {
                logger.debug("【查询重写】检测到指代词 '{}': '{}'", pronoun, question);
                return true;
            }
        }
        return false;
    }
}