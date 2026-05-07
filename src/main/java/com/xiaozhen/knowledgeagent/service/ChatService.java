package com.xiaozhen.knowledgeagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhen.knowledgeagent.model.Document;
import com.xiaozhen.knowledgeagent.repository.DocumentRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocumentRepository documentRepository;

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

    // 修改后的ask方法，支持会话历史
    public String ask(String sessionId, String question) {
        // 1. 从数据库读取激活的文档ID
        List<Document> activeDocs = documentRepository.findByActiveTrue();
        List<String> allChunks = new ArrayList<>();
        for (Document doc : activeDocs) {
            String json = redisTemplate.opsForValue().get("chunks:" + doc.getId());
            if (json != null) {
                try {
                    List<String> chunks = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                    allChunks.addAll(chunks);
                } catch (Exception e) {
                    // 跳过
                }
            }
        }

        if (allChunks.isEmpty()) {
            return "请先上传文档再提问";
        }

        // 2. 检索
        List<String> relevantChunks = vectorService.searchRelevant(allChunks, question, 3);
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < relevantChunks.size(); i++) {
            context.append("【参考片段").append(i + 1).append("】\n");
            context.append(relevantChunks.get(i)).append("\n\n");
        }

        // 3. 从Redis读取历史（存储为JSON字符串）
        String historyKey = "chat:history:" + sessionId;
        String historyJson = redisTemplate.opsForValue().get(historyKey);
        List<Map<String, String>> history = new ArrayList<>();
        if (historyJson != null) {
            try {
                history = objectMapper.readValue(historyJson, new TypeReference<List<Map<String, String>>>() {});
            } catch (Exception e) {
                // 解析失败就用空历史
            }
        }

        // 4. 构建带历史的Prompt
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
            // 提示AI要改进回答
            historyPrompt.append("【系统提示】\n").append(dislikeFeedback).append("\n\n");
            redisTemplate.delete(dislikeKey);
        }

        String prompt = """
                        你是一个专业、清晰的知识库助手。
                        
                        回答规则：
                        1. 默认使用分点形式回答，用"1."、"2."、"3."编号，简洁明了。
                        2. 如果用户明确要求"用一句话回答"或"用一段话总结"，则用完整的一段话来回复。
                        3. 回答只基于参考内容，不要编造信息。
                        4. 如果参考内容不足以回答问题，请如实告知。
                        5. 【重要】不要使用任何Markdown格式：不要用**加粗**、不要用*斜体*、不要用-或*列表符号、不要用#标题。直接输出纯文本。
                        
                        %s
                        【参考内容】
                        %s
                        
                        【用户当前问题】
                        %s
                        
                        【你的回答】
                        """
                .formatted(historyPrompt.toString(), context.toString(), question);

        String answer = model.generate(prompt);

        // === 拼接溯源信息 ===
        StringBuilder sourceInfo = new StringBuilder();
        sourceInfo.append("\n\n---\n📚 **参考来源**\n");
        Set<String> questionWords = tokenizeForHighlight(question);
        for (int i = 0; i < relevantChunks.size(); i++) {
            sourceInfo.append("\n**片段").append(i + 1).append("：** ");
            String highlighted = highlightKeywords(relevantChunks.get(i), questionWords);
            if (highlighted.length() > 120) {
                highlighted = highlighted.substring(0, 120) + "...";
            }
            sourceInfo.append(highlighted);
        }

        String finalAnswer = answer + sourceInfo.toString();

        // 5. 保存本轮对话
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
            // 序列化失败不影响主流程
        }

        return finalAnswer;
    }

    // 保留旧的ask方法兼容
    public String ask(String question) {
        return ask("default-session", question);
    }

    /**
     * 从问题中提取关键词（用于高亮）
     */
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

    /**
     * 在文本中高亮关键词（用【】包裹）
     */
    private String highlightKeywords(String text, Set<String> keywords) {
        String result = text;
        for (String keyword : keywords) {
            result = result.replaceAll("(?i)" + Pattern.quote(keyword), "【" + keyword + "】");
        }
        return result;
    }
}