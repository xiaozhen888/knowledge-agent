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
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Value("${langchain4j.openai.api-key}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.openai.model-name}")
    private String modelName;

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

        // ========== 【新增】第1步：查热点缓存 ==========
        CachedAnswer cached = hotCache.get(q);
        if (cached != null) {
            // 命中缓存，保存历史后直接返回（跳过敏检+向量+LLM）
            ChatHistory ch = new ChatHistory(sessionId, q, cached.getAnswer());
            chatHistoryRepository.save(ch);
            logger.info("🎯 热点缓存命中，question={}, 耗时={}ms", q, System.currentTimeMillis() - startTime);
            try {
                String historyKey = "chat:history:" + sessionId;
                String historyJson = redisTemplate.opsForValue().get(historyKey);
                List<Map<String, String>> history = historyJson != null
                        ? objectMapper.readValue(historyJson, new TypeReference<>(){})
                        : new ArrayList<>();

                Map<String, String> turn = new HashMap<>();
                turn.put("user", q);
                turn.put("assistant", cached.getAnswer());
                history.add(turn);
                if (history.size() > 10) history.remove(0);

                redisTemplate.opsForValue().set(historyKey, objectMapper.writeValueAsString(history), 30, TimeUnit.MINUTES);
            } catch (Exception ignored) {}
            return cached.getAnswer();
        }

        // ========== 第2步：未命中，走原有 RAG 流程（以下全部保持原样） ==========
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

        String questionEmbeddingKey = "embedding:query:" + sessionId;
        String cachedQueryJson = redisTemplate.opsForValue().get(questionEmbeddingKey);
        float[] queryVector;

        if (cachedQueryJson != null) {
            queryVector = objectMapper.readValue(cachedQueryJson, float[].class);
        } else {
            queryVector = embeddingService.embed(question);
            redisTemplate.opsForValue().set(questionEmbeddingKey, objectMapper.writeValueAsString(queryVector), 10, TimeUnit.MINUTES);
        }

        List<String> allContents = allChunkResults.stream()
                .map(ChunkResult::getContent)
                .collect(Collectors.toList());

        List<String> keywordContents = vectorService.searchRelevant(allContents, question, 15);
        Set<String> keywordSet = new HashSet<>(keywordContents);
        List<ChunkResult> keywordResults = allChunkResults.stream()
                .filter(c -> keywordSet.contains(c.getContent()))
                .collect(Collectors.toList());

        List<VectorService.ScoredChunk> vectorScored = vectorService.searchByEmbeddingWithScore(
                allEmbeddings, queryVector, 30, 0.0);

        List<ChunkResult> embeddingResults = new ArrayList<>();
        for (VectorService.ScoredChunk sc : vectorScored) {
            ChunkResult cr = allChunkResults.get(sc.index);
            cr.setScore(sc.score);
            embeddingResults.add(cr);
        }

        logger.info("关键词路召回: {} 个, 向量路召回: {} 个)",
                keywordResults.size(), embeddingResults.size());

        // ========== 4. 融合去重 ==========
        Set<String> seen = new HashSet<>();
        List<ChunkResult> allCandidates = new ArrayList<>();
        for (ChunkResult c : keywordResults) {
            if (seen.add(c.getContent())) allCandidates.add(c);
        }
        for (ChunkResult c : embeddingResults) {
            if (seen.add(c.getContent())) allCandidates.add(c);
        }

        logger.info("融合去重后候选集: {} 个", allCandidates.size());

        // ========== 新增：如果候选太多，只保留Top15 ==========
        if (allCandidates.size() > 8) {
            allCandidates = allCandidates.stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .limit(8)
                    .collect(Collectors.toList());
            logger.info("截断后候选集: {} 个", allCandidates.size());
        }

        // ========== 5. 精排结果缓存 ==========
        String rerankKey = "rerank:" + question.trim();
        String cachedRerankJson = redisTemplate.opsForValue().get(rerankKey);
        List<ChunkResult> relevantChunks;

        if (cachedRerankJson != null) {
            try {
                relevantChunks = objectMapper.readValue(cachedRerankJson, new TypeReference<List<ChunkResult>>() {});
                logger.info("🚀 精排结果缓存命中，跳过精排");
            } catch (Exception e) {
                logger.warn("精排缓存反序列化失败，重新精排", e);
                relevantChunks = rerankService.rerank(allCandidates, question, 8);
                // 缓存结果
                try {
                    redisTemplate.opsForValue().set(rerankKey, objectMapper.writeValueAsString(relevantChunks), 1, TimeUnit.HOURS);
                } catch (Exception ignored) {}
            }
        } else {
            relevantChunks = rerankService.rerank(allCandidates, question, 8);
            // 缓存结果
            try {
                redisTemplate.opsForValue().set(rerankKey, objectMapper.writeValueAsString(relevantChunks), 1, TimeUnit.HOURS);
            } catch (Exception ignored) {}
        }

        if (relevantChunks.isEmpty()) {
            logger.warn("精排后无有效候选，问题: {}", question);
            return "根据提供的文档，无法找到相关信息。";
        }

        logger.info("精排后Top片段:");
        for (int i = 0; i < Math.min(3, relevantChunks.size()); i++) {
            ChunkResult c = relevantChunks.get(i);
            String preview = c.getContent().substring(0, Math.min(80, c.getContent().length()));
            logger.info("  Top{}: score={}, content={}", i + 1, c.getScore(), preview);
        }

        StringBuilder context = new StringBuilder();
        int contextLimit = Math.min(relevantChunks.size(), 8); // 限制进 prompt 的片段数
        for (int i = 0; i < contextLimit; i++) {
            ChunkResult chunk = relevantChunks.get(i);
            context.append("{片段").append(i + 1).append("}\n");
            context.append(chunk.getContent()).append("\n");
            context.append("[来源: 《").append(chunk.getDocName())
                    .append("》 第").append(chunk.getChunkIndex()).append("段]\n\n");
        }

        String historyKey = "chat:history:" + sessionId;
        String historyJson = redisTemplate.opsForValue().get(historyKey);
        List<Map<String, String>> history = new ArrayList<>();

        if (historyJson != null) {
            try {
                history = objectMapper.readValue(historyJson, new TypeReference<List<Map<String, String>>>() {});
            } catch (Exception e) {
                history = new ArrayList<>();
            }
        } else {
            List<ChatHistory> dbHistories = chatHistoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            if (!dbHistories.isEmpty()) {
                for (ChatHistory h : dbHistories) {
                    Map<String, String> turn = new HashMap<>();
                    turn.put("user", h.getQuestion());
                    turn.put("assistant", h.getAnswer());
                    history.add(turn);
                }
                try {
                    redisTemplate.opsForValue().set(historyKey, objectMapper.writeValueAsString(history), 30, TimeUnit.MINUTES);
                } catch (Exception e) {
                }
            }
        }

        StringBuilder historyPrompt = new StringBuilder();
        if (!history.isEmpty()) {
            historyPrompt.append("【历史对话】\n");
            for (Map<String, String> turn : history) {
                historyPrompt.append("用户：").append(turn.get("user")).append("\n");
                historyPrompt.append("助手：").append(turn.get("assistant")).append("\n");
            }
            historyPrompt.append("\n");
        }

        String dislikeKey = "feedback:dislike:" + sessionId;
        String dislikeFeedback = redisTemplate.opsForValue().get(dislikeKey);
        if (dislikeFeedback != null) {
            historyPrompt.append("【系统提示】\n").append(dislikeFeedback).append("\n\n");
            redisTemplate.delete(dislikeKey);
        }

        String prompt = """
                        你是一位严谨的文档问答助手。请严格遵循以下规则：
                    
                        【规则】
                        1. 必须仅基于下方提供的【参考文档片段】回答问题，禁止引用片段外的任何知识
                        2. 如果参考片段无法回答问题，必须明确回答："根据提供的文档，无法找到相关信息"
                        3. 回答时必须标注信息来源，格式为：[来源: 《文档名称》 第{index}段]
                        4. 每个事实后必须紧跟来源标注，禁止在回答末尾统一标注
                        5. 禁止编造、推测、扩展文档中不存在的内容
                        6. 如果多个片段冲突，优先采用最新或最具体的片段
                    
                        %s
                        【参考文档片段】
                        %s
                    
                        【用户问题】
                        %s
                    
                        【回答要求】
                        - 先给出简洁答案（1-2句话）
                        - 如需详细说明，使用分点形式
                        - 每个事实后标注来源，格式：[来源: 《文档名称》 第N段]
                        - 禁止输出Markdown格式
                        - 禁止在回答末尾列出所有来源
                        """
                .formatted(historyPrompt.toString(), context.toString(), question);

        String answer = model.generate(prompt);

        // ========== 9. 尝试解析模型引用的来源（仅用于日志观察） ==========
        Set<String> citedSources = extractCitedSources(answer);
        logger.info("模型引用了 {} 个来源: {}", citedSources.size(), citedSources);

        // ========== 10. 拼接溯源信息：只展示模型实际引用的片段 ==========
        StringBuilder sourceInfo = new StringBuilder();

        // 严格过滤：只保留模型实际标注了来源的片段
        List<ChunkResult> citedChunks = new ArrayList<>();
        for (ChunkResult chunk : relevantChunks) {
            String sourceKey = chunk.getDocName() + ":" + chunk.getChunkIndex();
            if (citedSources.contains(sourceKey)) {
                citedChunks.add(chunk);
            }
        }

        // 如果模型一个都没标注，兜底展示Top3（避免完全没来源）
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

        ChatHistory ch = new ChatHistory(sessionId, question, answer);
        chatHistoryRepository.save(ch);

        Map<String, String> turn = new HashMap<>();
        turn.put("user", question);
        turn.put("assistant", answer);
        history.add(turn);
        if (history.size() > 10) {
            history.remove(0);
        }
        try {
            redisTemplate.opsForValue().set(historyKey, objectMapper.writeValueAsString(history), 30, TimeUnit.MINUTES);
        } catch (Exception e) {
        }

        // ========== 【新增】第3步：异步记录，自动晋升热点 ==========
        final String finalAnswerForCache = finalAnswer;
        final List<String> finalSources = citedSources.stream().toList();
        CompletableFuture.runAsync(() -> hotCache.recordQuestion(q, finalAnswerForCache, finalSources));

        return finalAnswer;
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
        String[] words = question.split("[，。！？；、\\s,.!?;:：\n]+");
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
        // 支持多种格式：
        // [来源: 《xxx》 第N段]
        // 【来源: 《xxx》 第N段】
        // 来源: 《xxx》 第N段
        // 《xxx》第N段
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
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(chunks), 1, TimeUnit.HOURS);
            logger.info("精排结果已缓存: {}", key);
        } catch (Exception e) {
            logger.warn("精排结果缓存失败", e);
        }
    }
}