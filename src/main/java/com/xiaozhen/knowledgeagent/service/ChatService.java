package com.xiaozhen.knowledgeagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhen.knowledgeagent.model.CachedAnswer;
import com.xiaozhen.knowledgeagent.model.ChunkResult;
import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.model.ChatHistory;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import com.xiaozhen.knowledgeagent.repository.ChatHistoryRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final VectorService vectorService;
    private final RedisTemplate<String, String> redisTemplate;
    private final DocumentRepository documentRepository;
    private final ChatHistoryRepository chatHistoryRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RerankService rerankService;
    private final HotQuestionCacheService hotCache;
    private final QueryRewriteService queryRewriteService;  // 【2.3】查询重写服务
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Value("${langchain4j.openai.api-key}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.openai.model-name}")
    private String modelName;

    // ========== 可配置的RAG参数 ==========
    private static final double VECTOR_MIN_SCORE = 0.45;
    private static final int windowSize = 100;
    private static final int stride = 50;
    private static final int topKPerWindow = 10;
    private static final int KEYWORD_TOP_K = 20;
    private static final int FUSION_MAX_CANDIDATES = 20;
    private static final int RERANK_TOP_K = 12;
    private static final int PROMPT_MAX_CHUNKS = 12;
    private static final int MAX_HISTORY_TURNS = 10;
    private static final int HISTORY_CACHE_MINUTES = 30;
    private static final int QUERY_EMBED_CACHE_MINUTES = 10;
    private static final int RERANK_CACHE_HOURS = 1;
    private static final double MIN_RERANK_SCORE = 0.3;

    private ChatLanguageModel model;

    @PostConstruct
    public void init() {
        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    public String ask(String sessionId, String question) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String q = question.trim();

        // ========== 第1步：查热点缓存（用原始问题）==========
        CachedAnswer cached = hotCache.get(q);
        if (cached != null) {
            ChatHistory ch = new ChatHistory(sessionId, q, cached.getAnswer());
            chatHistoryRepository.save(ch);
            logger.info("🎯 热点缓存命中，question={}, 耗时={}ms", q, System.currentTimeMillis() - startTime);
            updateRedisHistory(sessionId, q, cached.getAnswer());
            return cached.getAnswer();
        }

        // ========== 第2步：获取历史会话 ==========
        String historyKey = "chat:history:" + sessionId;
        String historyJson = redisTemplate.opsForValue().get(historyKey);
        List<Map<String, String>> history = new ArrayList<>();

        if (historyJson != null) {
            try {
                history = objectMapper.readValue(historyJson, new TypeReference<List<Map<String, String>>>() {});
            } catch (Exception e) {
                history = new ArrayList<>();
            }
        }

        // ========== 【2.3】第3步：查询重写 ==========
        String rewrittenQuestion = queryRewriteService.rewrite(q, history);
        logger.info(" 查询重写: '{}' → '{}'", q, rewrittenQuestion);

        // ========== 第4步：用重写后的问题做检索 ==========
        List<Document> activeDocs = documentRepository.findByActiveTrueAndDeletedFalse();

        List<ChunkResult> allChunkResults = new ArrayList<>();
        List<float[]> allEmbeddings = new ArrayList<>();

        for (Document doc : activeDocs) {
            String chunksJson = redisTemplate.opsForValue().get("chunks:" + doc.getId());
            String embJson = redisTemplate.opsForValue().get("embeddings:" + doc.getId());

            if (chunksJson != null && embJson != null) {
                try {
                    List<String> chunks = objectMapper.readValue(chunksJson, new TypeReference<List<String>>() {});
                    List<float[]> embeddings = objectMapper.readValue(embJson, new TypeReference<List<float[]>>() {});

                    if (chunks.size() == embeddings.size()) {
                        for (int i = 0; i < chunks.size(); i++) {
                            allChunkResults.add(new ChunkResult(chunks.get(i), doc.getId(), doc.getFileName(), i));
                            allEmbeddings.add(embeddings.get(i));
                        }
                    } else {
                        logger.warn("文档 {} 切片数({})和向量数({})不一致", doc.getId(), chunks.size(), embeddings.size());
                    }
                } catch (Exception e) {
                    logger.warn("解析文档 {} 的Redis数据失败: {}", doc.getId(), e.getMessage());
                }
            }
        }

        if (allChunkResults.isEmpty()) {
            return "请先上传文档再提问";
        }

        // ========== 第5步：获取问题向量（用重写后的问题）==========
        // 缓存key用重写后的问题的hash，避免和原始问题冲突
        String rewriteHash = String.valueOf(rewrittenQuestion.hashCode());
        String questionEmbeddingKey = "embedding:query:" + sessionId + ":" + rewriteHash;
        String cachedQueryJson = redisTemplate.opsForValue().get(questionEmbeddingKey);
        float[] queryVector;

        if (cachedQueryJson != null) {
            queryVector = objectMapper.readValue(cachedQueryJson, float[].class);
            logger.info("查询向量缓存命中（重写后）");
        } else {
            queryVector = embeddingService.embed(rewrittenQuestion);  // 用重写后的问题生成向量
            redisTemplate.opsForValue().set(questionEmbeddingKey, objectMapper.writeValueAsString(queryVector),
                    QUERY_EMBED_CACHE_MINUTES, TimeUnit.MINUTES);
        }

        // ========== 第6步：多路召回（都用重写后的问题）==========
        List<String> allContents = allChunkResults.stream()
                .map(ChunkResult::getContent)
                .collect(Collectors.toList());

        // 6.1 关键词路召回（用重写后的问题）
        List<String> keywordContents = vectorService.searchRelevant(allContents, rewrittenQuestion, KEYWORD_TOP_K);
        Set<String> keywordSet = new HashSet<>(keywordContents);
        List<ChunkResult> keywordResults = allChunkResults.stream()
                .filter(c -> keywordSet.contains(c.getContent()))
                .collect(Collectors.toList());

        // 6.2 向量路召回（滑动窗口，用重写后的问题生成的向量）
        List<ChunkResult> embeddingResults = searchWithSlidingWindow(
                allChunkResults, allEmbeddings, queryVector, rewrittenQuestion,  // 【2.3】传重写后的问题用于日志
                windowSize, stride, topKPerWindow, VECTOR_MIN_SCORE);

        logger.info("关键词路召回: {} 个, 向量路(滑动窗口)召回: {} 个 (阈值:{})",
                keywordResults.size(), embeddingResults.size(), VECTOR_MIN_SCORE);

        // ========== 第7步：融合去重（粗排） ==========
        Set<String> seen = new HashSet<>();
        List<ChunkResult> allCandidates = new ArrayList<>();

        for (ChunkResult c : embeddingResults) {
            if (seen.add(c.getContent())) allCandidates.add(c);
        }
        for (ChunkResult c : keywordResults) {
            if (seen.add(c.getContent())) {
                c.setScore(0.5);
                allCandidates.add(c);
            }
        }

        logger.info("融合去重后候选集: {} 个", allCandidates.size());

        if (allCandidates.size() > FUSION_MAX_CANDIDATES) {
            allCandidates = allCandidates.stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .limit(FUSION_MAX_CANDIDATES)
                    .collect(Collectors.toList());
            logger.info("粗排截断后候选集: {} 个 (阈值:{})", allCandidates.size(), FUSION_MAX_CANDIDATES);
        }

        // ========== 第8步：精排（缓存key用重写后的问题）==========
        String rerankKey = "rerank:" + rewrittenQuestion.trim();  // 用重写后的问题做缓存key
        String cachedRerankJson = redisTemplate.opsForValue().get(rerankKey);
        List<ChunkResult> relevantChunks;

        if (cachedRerankJson != null) {
            try {
                relevantChunks = objectMapper.readValue(cachedRerankJson, new TypeReference<List<ChunkResult>>() {});
                logger.info("🚀 精排结果缓存命中，跳过精排");
            } catch (Exception e) {
                logger.warn("精排缓存反序列化失败，重新精排", e);
                relevantChunks = rerankService.rerank(allCandidates, rewrittenQuestion, RERANK_TOP_K);  // 【2.3】用重写后的问题精排
                cacheRerankResult(rerankKey, relevantChunks);
            }
        } else {
            relevantChunks = rerankService.rerank(allCandidates, rewrittenQuestion, RERANK_TOP_K);  // 【2.3】用重写后的问题精排
            cacheRerankResult(rerankKey, relevantChunks);
        }

        if (relevantChunks.isEmpty()) {
            logger.warn("精排后无有效候选（全部低于阈值{}），问题: {}", rewrittenQuestion);
            return "根据提供的文档，无法找到相关信息。";
        }

        // ========== 精排后过滤低分片段 ==========
        List<ChunkResult> filteredChunks = relevantChunks.stream()
                .filter(c -> c.getScore() >= MIN_RERANK_SCORE)
                .collect(Collectors.toList());

        logger.info("精排过滤前: {} 个, 过滤后: {} 个 (阈值:{})",
                relevantChunks.size(), filteredChunks.size(), MIN_RERANK_SCORE);

        if (filteredChunks.isEmpty()) {
            logger.warn("精排后无有效候选（全部低于阈值{}），问题: {}", MIN_RERANK_SCORE, rewrittenQuestion);
            filteredChunks = relevantChunks.subList(0, Math.min(3, relevantChunks.size()));
        }
        relevantChunks = filteredChunks;

        // ========== 计算进入 Prompt 的片段数 ==========
        int contextLimit = Math.min(relevantChunks.size(), PROMPT_MAX_CHUNKS);
        logger.info("本次进入Prompt的片段数: {}/{}", contextLimit, relevantChunks.size());

        // ========== 打印 Top 片段 ==========
        logger.info("精排后Top片段 (共{}个进Prompt):", contextLimit);
        for (int i = 0; i < contextLimit; i++) {
            ChunkResult c = relevantChunks.get(i);
            String preview = c.getContent().substring(0, Math.min(60, c.getContent().length()));
            logger.info("  Top{}: score={}, doc={}, chunk={}, content={}",
                    i + 1, String.format("%.3f", c.getScore()), c.getDocName(), c.getChunkIndex(), preview);
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < contextLimit; i++) {
            ChunkResult chunk = relevantChunks.get(i);
            context.append("{片段").append(i + 1).append("} ");
                    context.append(chunk.getContent()).append(" ");
                            context.append("[来源: 《").append(chunk.getDocName())
                                    .append("》 第").append(chunk.getChunkIndex()).append("段] ");
        }

        // ========== 第9步：构建历史对话 Prompt ==========
        StringBuilder historyPrompt = new StringBuilder();
        if (!history.isEmpty()) {
            historyPrompt.append("【历史对话】 ");
            for (Map<String, String> turn : history) {
                historyPrompt.append("用户：").append(turn.get("user")).append(" ");
                        historyPrompt.append("助手：").append(turn.get("assistant")).append(" ");
            }
            historyPrompt.append(" ");
        }

        String dislikeKey = "feedback:dislike:" + sessionId;
        String dislikeFeedback = redisTemplate.opsForValue().get(dislikeKey);
        if (dislikeFeedback != null) {
            historyPrompt.append("【系统提示】").append(dislikeFeedback).append(" ");
                    redisTemplate.delete(dislikeKey);
        }

        // ========== 动态调整进 Prompt 的片段数 ==========
        if (relevantChunks.get(0).getScore() > 0.9) {
            contextLimit = Math.min(3, relevantChunks.size());
        } else if (relevantChunks.get(0).getScore() > 0.5) {
            contextLimit = Math.min(8, relevantChunks.size());
        } else {
            contextLimit = Math.min(PROMPT_MAX_CHUNKS, relevantChunks.size());
        }
        logger.info("动态调整进Prompt片段数: {}/{} (Top1分数:{})",
                contextLimit, relevantChunks.size(), String.format("%.3f", relevantChunks.get(0).getScore()));

        // ========== 第10步：构建Prompt（显示用原始问题，检索用重写后的问题）==========
        String prompt = """
                        你是一位严谨的文档问答助手。请严格遵循以下规则：

                        【规则】
                        1. 必须仅基于下方提供的【参考文档片段】回答问题
                        2.【重要】如果片段中包含与问题相关的信息，即使不是直接回答，也请提取并整合后回答，不要直接说"无法找到"
                        3. 如果片段中确实完全没有相关信息，再回答："根据提供的文档，无法找到相关信息"
                        4. 回答时必须标注信息来源，格式为：[来源: 《文档名称》 第{index}段]
                        5. 每个事实后必须紧跟来源标注，禁止在回答末尾统一标注
                        6. 禁止编造、推测、扩展文档中不存在的内容
                        7.【重要】如果答案分布在多个片段中，请阅读相关片段后，拼接完整信息后再回答，禁止只用部分片段回答
                        8.【重要】不要过度追求简洁，请给出完整、详细的回答，确保覆盖所有相关片段中的关键信息
                        9.【重要】禁止在回答中写"原文在此处截断""未完待续"等提示语，必须根据已有片段给出完整回答

                        %s
                        【参考文档片段】
                        %s

                        【用户问题】
                        %s

                        【回答要求】
                        - 仔细阅读所有片段，判断哪些片段共同回答了问题
                        - 如果多个片段内容互补，请合并后给出完整回答
                        - 每个事实后标注来源，格式：[来源: 《文档名称》 第N段]
                        - 禁止输出Markdown格式
                        - 禁止在回答末尾列出所有来源
                """
                .formatted(historyPrompt.toString(), context.toString(), q);  // 【2.3】Prompt里用原始问题q

        String answer = model.generate(prompt);

        // ========== 第11步：解析引用来源 ==========
        Set<String> citedSources = extractCitedSources(answer);
        logger.info("模型引用了 {} 个来源: {}", citedSources.size(), citedSources);

        // ========== 第12步：拼接溯源信息 ==========
        StringBuilder sourceInfo = new StringBuilder();

        List<ChunkResult> citedChunks = new ArrayList<>();
        for (ChunkResult chunk : relevantChunks) {
            String sourceKey = chunk.getDocName() + ":" + chunk.getChunkIndex();
            if (citedSources.contains(sourceKey)) {
                citedChunks.add(chunk);
            }
        }

        if (citedChunks.isEmpty()) {
            logger.warn("模型未标注任何来源，兜底展示Top3");
            citedChunks = relevantChunks.subList(0, Math.min(3, relevantChunks.size()));
        }

        if (!citedChunks.isEmpty()) {
            sourceInfo.append("\n\n---\n📚 **参考来源**\n");
            for (int i = 0; i < citedChunks.size(); i++) {
                ChunkResult chunk = citedChunks.get(i);
                sourceInfo.append("\n【《").append(chunk.getDocName())
                        .append("》第").append(chunk.getChunkIndex()).append("段】\n")
                        .append(chunk.getContent());
            }
        }

        String finalAnswer = answer + sourceInfo.toString();

        // ========== 第13步：保存历史（保存原始问题）==========
        ChatHistory ch = new ChatHistory(sessionId, q, answer);  // 【2.3】保存原始问题
        chatHistoryRepository.save(ch);

        updateRedisHistory(sessionId, q, answer);  // 【2.3】Redis历史也用原始问题

        // ========== 第14步：异步记录热点（用原始问题）==========
        final String finalAnswerForCache = finalAnswer;
        final List<String> finalSources = citedSources.stream().toList();
        CompletableFuture.runAsync(() -> hotCache.recordQuestion(q, finalAnswerForCache, finalSources));  // 【2.3】热点缓存用原始问题

        return finalAnswer;
    }

    /**
     * 更新Redis中的对话历史
     */
    private void updateRedisHistory(String sessionId, String question, String answer) {
        try {
            String historyKey = "chat:history:" + sessionId;
            String historyJson = redisTemplate.opsForValue().get(historyKey);
            List<Map<String, String>> history = historyJson != null
                    ? objectMapper.readValue(historyJson, new TypeReference<List<Map<String, String>>>() {})
                    : new ArrayList<>();

            Map<String, String> turn = new HashMap<>();
            turn.put("user", question);
            turn.put("assistant", answer);
            history.add(turn);
            if (history.size() > MAX_HISTORY_TURNS) {
                history.remove(0);
            }

            redisTemplate.opsForValue().set(historyKey, objectMapper.writeValueAsString(history),
                    HISTORY_CACHE_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.warn("更新Redis历史失败", e);
        }
    }

    public String ask(String question) {
        try {
            return ask("default-session", question);
        } catch (JsonProcessingException e) {
            return "系统内部错误: " + e.getMessage();
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private Set<String> tokenizeForHighlight(String question) {
        String[] words = question.split("[，。！？；、\s,.!?;:：\n]+");
        Set<String> result = new HashSet<>();
        for (String word : words) {
            if (word.trim().length() >= 2) {
                result.add(word.trim());
            }
        }
        return result;
    }

    private String highlightKeywords(String text, Set<String> keywords) {
        String result = text;
        for (String keyword : keywords) {
            result = result.replaceAll("(?i)" + Pattern.quote(keyword), "【" + keyword + "】");
        }
        return result;
    }

    private Set<String> extractCitedSources(String answer) {
        Set<String> sources = new HashSet<>();
        Pattern pattern = Pattern.compile(
                "[【\\[]?来源[:：]?\\s*《(.+?)》\\s*第\\s*(\\d+)\\s*段[】\\]]?"
        );
        Matcher matcher = pattern.matcher(answer);
        while (matcher.find()) {
            String docName = matcher.group(1).trim();
            String index = matcher.group(2).trim();
            sources.add(docName + ":" + index);
        }
        return sources;
    }

    /**
     * 缓存精排结果到Redis
     */
    private void cacheRerankResult(String key, List<ChunkResult> chunks) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(chunks),
                    RERANK_CACHE_HOURS, TimeUnit.HOURS);
            logger.info("精排结果已缓存: {}", key);
        } catch (Exception e) {
            logger.warn("精排结果缓存失败", e);
        }
    }

    /**
     * 滑动窗口检索：将长文档分成多个重叠窗口，每个窗口单独检索，避免前面片段淹没后面片段
     */
    private List<ChunkResult> searchWithSlidingWindow(
            List<ChunkResult> allChunks,
            List<float[]> allEmbeddings,
            float[] queryVector,
            String question,
            int windowSize,
            int stride,
            int topKPerWindow,
            double minScore) {

        int totalChunks = allChunks.size();
        List<ChunkResult> allWindowResults = new ArrayList<>();

        logger.info("滑动窗口检索开始: 总片段{}个, 窗口大小{}, 步长{}", totalChunks, windowSize, stride);

        for (int start = 0; start < totalChunks; start += stride) {
            int end = Math.min(start + windowSize, totalChunks);

            List<ChunkResult> windowChunks = allChunks.subList(start, end);
            List<float[]> windowEmbeddings = allEmbeddings.subList(start, end);

            List<VectorService.ScoredChunk> windowScored = vectorService.searchByEmbeddingWithScore(
                    windowEmbeddings, queryVector, topKPerWindow, minScore);

            for (VectorService.ScoredChunk sc : windowScored) {
                int globalIndex = start + sc.index;
                ChunkResult cr = allChunks.get(globalIndex);
                cr.setScore(sc.score);
                allWindowResults.add(cr);
            }

            logger.debug("窗口 [{}-{}] 检索到 {} 个结果", start, end - 1, windowScored.size());
        }

        Set<String> seen = new HashSet<>();
        List<ChunkResult> uniqueResults = allWindowResults.stream()
                .filter(c -> seen.add(c.getContent()))
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        logger.info("滑动窗口检索完成: 共{}个窗口, 合并去重后{}个结果",
                (totalChunks + stride - 1) / stride, uniqueResults.size());

        return uniqueResults;
    }
}