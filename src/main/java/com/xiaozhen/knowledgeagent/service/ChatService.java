package com.xiaozhen.knowledgeagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
        // ========== 1. 从数据库读取激活且未删除的文档切片 ==========
        List<Document> activeDocs = documentRepository.findByActiveTrueAndDeletedFalse();
        List<ChunkResult> allChunkResults = new ArrayList<>();
        for (Document doc : activeDocs) {
            String json = redisTemplate.opsForValue().get("chunks:" + doc.getId());
            if (json != null) {
                List<String> chunks = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                for (int i = 0; i < chunks.size(); i++) {
                    allChunkResults.add(new ChunkResult(chunks.get(i), doc.getId(), doc.getFileName(), i));
                }
            }
        }

        if (allChunkResults.isEmpty()) {
            return "请先上传文档再提问";
        }

        // ========== 2. 双路检索 + 融合排序 ==========
        // 获取问题向量（优先从Redis缓存读）
        String questionEmbeddingKey = "embedding:query:" + sessionId;
        String cachedQueryJson = redisTemplate.opsForValue().get(questionEmbeddingKey);
        float[] queryVector;

        if (cachedQueryJson != null) {
            queryVector = objectMapper.readValue(cachedQueryJson, float[].class);
        } else {
            queryVector = embeddingService.embed(question);
            redisTemplate.opsForValue().set(questionEmbeddingKey, objectMapper.writeValueAsString(queryVector), 10, TimeUnit.MINUTES);
        }

        // 关键词路：Top-15
        List<String> allContents = allChunkResults.stream()
                .map(ChunkResult::getContent)
                .collect(java.util.stream.Collectors.toList());

        List<String> keywordContents = vectorService.searchRelevant(allContents, question, 15);
        Set<String> keywordSet = new HashSet<>(keywordContents);
        List<ChunkResult> keywordResults = allChunkResults.stream()
                .filter(c -> keywordSet.contains(c.getContent()))
                .collect(java.util.stream.Collectors.toList());

        // 向量路：读取所有激活文档的向量，做语义检索
        List<String> embeddingContents = new ArrayList<>();
        for (Document doc : activeDocs) {
            String embJson = redisTemplate.opsForValue().get("embeddings:" + doc.getId());
            String chunksJson = redisTemplate.opsForValue().get("chunks:" + doc.getId());

            if (embJson != null && chunksJson != null) {
                try {
                    List<float[]> docEmbeddings = objectMapper.readValue(embJson, new TypeReference<List<float[]>>() {});
                    List<String> docChunks = objectMapper.readValue(chunksJson, new TypeReference<List<String>>() {});
                    List<String> topFromDoc = vectorService.searchByEmbedding(docChunks, docEmbeddings, queryVector, 15);
                    embeddingContents.addAll(topFromDoc);
                } catch (Exception e) {
                    // 跳过解析失败的
                }
            }
        }

        Set<String> embeddingSet = new HashSet<>(embeddingContents);
        List<ChunkResult> embeddingResults = allChunkResults.stream()
                .filter(c -> embeddingSet.contains(c.getContent()))
                .collect(java.util.stream.Collectors.toList());


        // 去重（按 content 去重，保留第一个出现的）
        Set<String> seen = new HashSet<>();
        List<ChunkResult> allCandidates = new ArrayList<>();
        for (ChunkResult c : keywordResults) {
            if (seen.add(c.getContent())) allCandidates.add(c);
        }
        for (ChunkResult c : embeddingResults) {
            if (seen.add(c.getContent())) allCandidates.add(c);
        }

        List<ChunkResult> relevantChunks = rerankService.rerank(allCandidates, question, 3);

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < relevantChunks.size(); i++) {
            ChunkResult chunk = relevantChunks.get(i);
            context.append("{片段").append(i + 1).append("}\n");
            context.append(chunk.getContent()).append("\n");
            context.append("[来源: 《").append(chunk.getDocName())
                    .append("》 第").append(chunk.getChunkIndex()).append("段]\n\n");
        }

        // ========== 3. 读历史：优先Redis，未命中查MySQL并回填 ==========
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
                    // 回填失败不影响
                }
            }
        }

        // ========== 4. 构建带历史的Prompt ==========
        StringBuilder historyPrompt = new StringBuilder();
        if (!history.isEmpty()) {
            historyPrompt.append("【历史对话】\n");
            for (Map<String, String> turn : history) {
                historyPrompt.append("用户：").append(turn.get("user")).append("\n");
                historyPrompt.append("助手：").append(turn.get("assistant")).append("\n");
            }
            historyPrompt.append("\n");
        }

        // === 差评反馈逻辑 ===
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
                        4. 禁止编造、推测、扩展文档中不存在的内容
                        5. 如果多个片段冲突，优先采用最新或最具体的片段
                    
                        %s
                        【参考文档片段】
                        %s
                    
                        【用户问题】
                        %s
                    
                        【回答要求】
                        - 先给出简洁答案（1-2句话）
                        - 如需详细说明，使用分点形式
                        - 每个事实后标注来源
                        - 禁止输出Markdown格式
                        """
                .formatted(historyPrompt.toString(), context.toString(), question);

        String answer = model.generate(prompt);

        // ========== 5. 解析回答中实际引用的来源 ==========
        Set<String> citedSources = extractCitedSources(answer);
        logger.info("模型引用了 {} 个来源: {}", citedSources.size(), citedSources);

        // ========== 6. 拼接溯源信息（只展示被引用的） ==========
        StringBuilder sourceInfo = new StringBuilder();

        // 过滤：只保留被模型实际引用的片段
        List<ChunkResult> citedChunks = new ArrayList<>();
        for (ChunkResult chunk : relevantChunks) {
            String sourceKey = chunk.getDocName() + ":" + chunk.getChunkIndex();
            // 如果模型有标注来源，且匹配当前片段，才展示
            if (citedSources.isEmpty() || citedSources.contains(sourceKey)) {
                citedChunks.add(chunk);
            }
        }

        // 如果模型一个都没标注，兜底展示全部（避免空来源）
        if (citedChunks.isEmpty()) {
            citedChunks = relevantChunks;
        }

        if (!citedChunks.isEmpty()) {
            sourceInfo.append("\n\n---\n📚 **参考来源**\n");
            for (int i = 0; i < citedChunks.size(); i++) {
                ChunkResult chunk = citedChunks.get(i);
                int idx = chunk.getChunkIndex();

                sourceInfo.append("\n**片段").append(i + 1).append("**（《")
                        .append(chunk.getDocName()).append("》第").append(idx).append("段）：")
                        .append(truncate(chunk.getContent().replace("\n", "<br>"), 800));
            }
        }


        String finalAnswer = answer + sourceInfo.toString();

        // ========== 7. 双写对话历史：MySQL + Redis ==========
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
            // Redis写失败不影响，下次读时会从MySQL回填
        }

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

    // ========== 高亮相关方法 ==========
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

    /**
     * 从模型回答中提取引用的来源
     * 格式: [来源: 《文档名称》 第N段]
     * 返回: Set<"文档名称:段号">
     */
    private Set<String> extractCitedSources(String answer) {
        Set<String> sources = new HashSet<>();
        // 匹配 [来源: 《八股文.pdf》 第130段]
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\[来源: 《(.+?)》 第(\\d+)段\\]"
        );
        java.util.regex.Matcher matcher = pattern.matcher(answer);
        while (matcher.find()) {
            String docName = matcher.group(1);
            String index = matcher.group(2);
            sources.add(docName + ":" + index);
        }
        return sources;
    }
}