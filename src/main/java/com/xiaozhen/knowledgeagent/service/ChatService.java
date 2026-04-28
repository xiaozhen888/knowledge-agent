package com.xiaozhen.knowledgeagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final VectorService vectorService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<String> allChunks = new ArrayList<>();

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

    public void storeChunks(List<String> chunks) {
        this.allChunks = chunks;
    }

    // 修改后的ask方法，支持会话历史
    public String ask(String sessionId, String question) {
        if (allChunks.isEmpty()) {
            return "请先上传文档再提问";
        }

        // 1. 检索
        List<String> relevantChunks = vectorService.searchRelevant(allChunks, question, 3);
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < relevantChunks.size(); i++) {
            context.append("【参考片段").append(i + 1).append("】\n");
            context.append(relevantChunks.get(i)).append("\n\n");
        }

        // 2. 从Redis读取历史（存储为JSON字符串）
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

        // 3. 构建带历史的Prompt
        StringBuilder historyPrompt = new StringBuilder();
        if (!history.isEmpty()) {
            historyPrompt.append("【历史对话】\n");
            for (Map<String, String> turn : history) {
                historyPrompt.append("用户：").append(turn.get("user")).append("\n");
                historyPrompt.append("助手：").append(turn.get("assistant")).append("\n");
            }
            historyPrompt.append("\n");
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

        // 4. 保存本轮对话
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

        return answer;
    }

    // 保留旧的ask方法兼容
    public String ask(String question) {
        return ask("default-session", question);
    }
}